# Open Chess Tournament

Backend for managing chess tournaments with **Swiss-system** pairing. Exposed as a REST API.

## Stack

- Java 25 / Spring Boot 4.1
- PostgreSQL 16 (via Docker Compose)
- Flyway for database versioning
- JGraphT (Blossom V weighted matching) for the pairing engine
- DDD architecture

## Running

```bash
# 1. Start the database
docker compose up -d

# 2. Start the application (Flyway applies migrations automatically on startup)
mvn spring-boot:run
```

The API is available at `http://localhost:8080`.

**Tests** (the context test uses Testcontainers and requires Docker running):

```bash
mvn test
```

**Recreate the database from scratch** (deletes all data):

```bash
docker compose down -v && docker compose up -d
mvn spring-boot:run
```

## Architecture

Code organized in DDD layers under `src/main/java/com/open/chess/tournament/`:

| Layer | Package | Contents |
|---|---|---|
| Domain | `domain` | `Tournament` aggregate (root) with `Player`, `Round` and `Pairing`; `SwissPairingEngine` and `TrfExporter` domain services (pure, framework-free); exceptions and repository interface |
| Application | `application` | `TournamentService` (use cases, transactions) and output DTOs |
| Infrastructure | `infrastructure` | Spring Data JPA repository and bean configuration |
| Interface | `interfaces/rest` | REST controllers, validated input DTOs and global error handler |

All business rules live in the domain. Scores and standings are **computed** from pairing results (never stored), eliminating any risk of inconsistency.

## Swiss pairing engine

`SwissPairingEngine` implements the FIDE (Dutch) Swiss criteria as a
**graph problem**: every round is a *minimum-weight perfect matching*,
solved with the Blossom V algorithm (JGraphT's
`KolmogorovWeightedPerfectMatching`). This is the same architecture used
by FIDE-endorsed engines such as bbpPairings, and it makes the pairing
**globally optimal**: instead of pairing the top player first and hoping
the rest works out (a greedy approach can paint itself into a corner),
the engine evaluates the round as a whole.

### The graph model

- **Vertices** — one per active player, ranked by score, then rating.
  With an odd number of players, one extra *virtual bye vertex* is added.
- **Edges** — only between players allowed to meet. The FIDE *absolute*
  criteria are encoded as **missing edges**: two players who already met
  get no edge (C.1 — a forfeited game still counts as having met), and
  the bye vertex only connects to players who never had a bye nor won by
  forfeit (C.2 / C.04.1.d). If the resulting graph has no perfect
  matching, no legal round exists and the tournament finishes.
- **Weights** — the FIDE *quality* criteria, encoded in strictly
  decreasing tiers so that the matching minimizes them lexicographically
  (no amount of a lower tier can outweigh a single unit of a higher one):

| Tier | FIDE criterion | Cost per edge |
|---|---|---|
| 1 | C.3 — both players due the same **absolute** color (balance ≥ 2 or same color twice in a row) | `6e9` |
| 2 | C.6 — score difference, **quadratic**: `5e6 × (2Δ)²` (capped) | `5e6`–`1.8e8` |
| 3 | C.8 — both players due the same color | `1e5` (+`5e4` if both strong) |
| 4 | C.12/C.13 — repeating a down/upfloat from the previous round | `1200` |
| 5 | D — fold order (S1×S2) deviation, same-half "exchange" pairs, floater position, bye rank | `1`–`20` |

The quadratic score term means two 1-point floats (`2 × 4 units`) are
cheaper than one 2-point float (`16 units`) — exactly the FIDE
preference for spreading small score differences instead of
concentrating a large one. Tier 1 is a *penalty* rather than a missing
edge on purpose: when no legal round avoids pairing two players due the
same absolute color (e.g. only two players left), the rule is relaxed —
matching FIDE's own escape hatch — and the higher-ranked player receives
their due color.

### Worked example

Four players after some rounds: `A` has 2.0 points and **already played
C**, `B` and `C` have 1.0, `D` has 0.0.

```
        A (2.0)
       ╱       ╲
  2e7 ╱         ╲ 8e7          A–C: no edge (rematch)
     ╱           ╲             A–B: Δ=1.0 → 5e6 × (2·1)²  = 2e7
    B (1.0)       D (0.0)      A–D: Δ=2.0 → 5e6 × (2·2)²  = 8e7
    │   ╲        ╱             B–C: same group, fold order = 0
  0 │    ╲ 2e7  ╱ 2e7          B–D: Δ=1.0                  = 2e7
    │     ╲    ╱               C–D: Δ=1.0                  = 2e7
    C (1.0) ──╯
```

Two perfect matchings exist:

| Matching | Cost |
|---|---|
| **{A–B, C–D}** | 2e7 + 2e7 = **4e7** ✓ chosen |
| {A–D, B–C} | 8e7 + 0 = 8e7 |

A greedy engine that "sends the leader down and pairs the rest" could
happily produce the second round; the matching provably never does.

Colors are assigned *after* the matching (FIDE E rules): grant absolute
preferences, then the larger color imbalance, then alternate from the
last played color; in round one, colors alternate down the boards. A
player with no played games (only byes/forfeits) has **no** color
preference at all.

### Gotcha: the Blossom V `1e10` threshold

JGraphT's `KolmogorovWeightedPerfectMatching` declares:

```java
public static final double NO_PERFECT_MATCHING_THRESHOLD = 1.0E10;
```

When the algorithm's dual variables grow past this value it gives up and
throws *"There is no perfect matching in the specified graph"* — **even
when one exists**. Dual values scale with the edge weights, so any
weight near or above `1e10` can trigger a false negative. This bit us
directly: the tiers were originally spaced up to `1e14` and a plain
3-player round (a 4-vertex graph with an obvious perfect matching) was
reported as unpairable.

The fix was recalibrating all tiers so the maximum edge weight stays
around `6e9`. The trade-off: each tier's unit must still exceed the
maximum possible *sum* of all lower tiers across a round, and with only
~9 usable orders of magnitude that arithmetic holds for fields of up to
**64 players**. Beyond that the weights remain a faithful approximation,
but the tier separation is no longer mathematically strict. If you touch
the `COST_*` constants, re-check both bounds — and run the JaVaFo
harness (below) to catch regressions.

### Rules implemented

- **No rematches, ever** (C.1); forfeited games count as a meeting.
- **Bye** (1 point) at most once per player, never to a forfeit winner,
  preferring the lowest-ranked player of the lowest score group (C.2).
- **Forfeits**: `+/-` results score like wins/losses but are *unplayed*
  — they do not affect color history and are excluded from "played each
  other once" only in the color sense, not the pairing sense.
- **Colors**: balance never exceeds ±2 and no player gets the same color
  three times in a row (absolute preference), verified by a simulated
  33-player, 9-round tournament in the test suite.
- **Floats**: down/upfloats are not repeated in consecutive rounds when
  a same-cost alternative exists.
- **Tie-breaks**: direct encounter, Buchholz, median Buchholz,
  Sonneborn-Berger, number of wins, rating. Unplayed games (byes,
  forfeits) are replaced by the FIDE **virtual opponent** (C.04.5): the
  player's score before the round, plus the complement of the result,
  plus half a point per remaining round.

### Validation against JaVaFo

`TrfExporter` exports any tournament as a FIDE **TRF16** report file
(the format used for FIDE rating submission), which feeds
`JaVaFoComparisonTest`: simulated tournaments where every round is also
paired by [JaVaFo](http://www.rrweb.org/javafo/JaVaFo.htm), the FIDE
reference implementation, from the exact same position. The harness
asserts identical first rounds, prints every divergence and enforces a
minimum agreement floor. See `tools/javafo/README.md` for setup (the JAR
is downloaded separately and gitignored); without it the test is skipped.

Divergences in later rounds are expected: among *equally optimal*
pairings the Dutch rules pick a canonical one via their transposition
order (D.1/D.2), which only a bit-exact reimplementation reproduces.
Every generated round is still fully legal.

## Tournament lifecycle

```
REGISTRATION ──start──▶ IN_PROGRESS ──▶ FINISHED
```

- `REGISTRATION`: accepts player registrations (minimum 2 to start).
- `IN_PROGRESS`: rounds are generated one at a time; the next one can only be generated once all results of the current round are reported.
- `FINISHED`: reached when the last result of the last round is reported, **or** automatically when no rematch-free pairing is possible.

---

# API

Base path: `/api/tournaments`. Bodies in JSON (`Content-Type: application/json`).

## Errors

Errors follow the [Problem Details (RFC 9457)](https://www.rfc-editor.org/rfc/rfc9457) standard:

| Status | When |
|---|---|
| `400 Bad Request` | Invalid body (field validation: blank name, `totalRounds < 1`, etc.) |
| `404 Not Found` | Tournament, round or pairing does not exist |
| `422 Unprocessable Content` | Business rule violation (e.g. generating a round with pending results) |

Example:

```json
{
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Round 2 still has pending results",
  "instance": "/api/tournaments/5ab6ff84-.../rounds"
}
```

---

## 1. Create tournament

`POST /api/tournaments` → `201 Created`

**Request:**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `name` | string | yes | must not be blank |
| `totalRounds` | int | yes | ≥ 1 |

```bash
curl -X POST http://localhost:8080/api/tournaments \
  -H 'Content-Type: application/json' \
  -d '{"name": "City Open", "totalRounds": 5}'
```

**Response:**

```json
{
  "id": "5ab6ff84-afd0-4899-8c01-10be61da950b",
  "name": "City Open",
  "totalRounds": 5,
  "roundsGenerated": 0,
  "status": "REGISTRATION",
  "playerCount": 0,
  "createdAt": "2026-07-09T01:40:18.954569Z"
}
```

## 2. List tournaments

`GET /api/tournaments` → `200 OK`

```bash
curl http://localhost:8080/api/tournaments
```

**Response:** array of tournaments in the same format as above.

## 3. Get tournament

`GET /api/tournaments/{tournamentId}` → `200 OK`

```bash
curl http://localhost:8080/api/tournaments/5ab6ff84-afd0-4899-8c01-10be61da950b
```

## 4. Register player

`POST /api/tournaments/{tournamentId}/players` → `201 Created`

Only allowed while the tournament is in `REGISTRATION`.

**Request:**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `name` | string | yes | must not be blank |
| `rating` | int | yes | ≥ 0 |

```bash
curl -X POST http://localhost:8080/api/tournaments/{tournamentId}/players \
  -H 'Content-Type: application/json' \
  -d '{"name": "Alice", "rating": 2100}'
```

**Response:**

```json
{
  "id": "73493154-3a5d-4736-b7cb-00b7b1a7c194",
  "name": "Alice",
  "rating": 2100,
  "active": true
}
```

## 5. List players

`GET /api/tournaments/{tournamentId}/players` → `200 OK`

```bash
curl http://localhost:8080/api/tournaments/{tournamentId}/players
```

**Response:** array of players in the format above.

## 6. Start tournament

`POST /api/tournaments/{tournamentId}/start` → `200 OK`

Requires at least 2 registered players. After the start, registrations are blocked.

```bash
curl -X POST http://localhost:8080/api/tournaments/{tournamentId}/start
```

**Response:** the tournament with `status: "IN_PROGRESS"`.

## 7. Generate next round

`POST /api/tournaments/{tournamentId}/rounds`

Generates the next round's pairings using the Swiss system. Rules:

- The tournament must be `IN_PROGRESS`;
- The previous round (if any) must have all results reported;
- Cannot exceed `totalRounds`.

The response is an envelope with two possible outcomes:

**a) Round generated** → `201 Created`

```bash
curl -X POST http://localhost:8080/api/tournaments/{tournamentId}/rounds
```

```json
{
  "tournamentStatus": "IN_PROGRESS",
  "round": {
    "id": "392c9ad7-5e46-4ed6-b887-7ac9b819c6e5",
    "number": 1,
    "complete": false,
    "pairings": [
      {
        "id": "d94f0162-bbb7-476b-8319-f6ca4cde7a88",
        "board": 1,
        "whitePlayerId": "73493154-3a5d-4736-b7cb-00b7b1a7c194",
        "whitePlayerName": "Alice",
        "blackPlayerId": "bf18e451-fbda-4740-ba1e-80118385c881",
        "blackPlayerName": "Carla",
        "result": "PENDING",
        "bye": false
      },
      {
        "id": "cd6c4bff-83e0-4969-9bf3-7dea0394867d",
        "board": 3,
        "whitePlayerId": "3d07e517-4d9d-4864-8a2a-e35e1cdb29e5",
        "whitePlayerName": "Elisa",
        "blackPlayerId": null,
        "blackPlayerName": null,
        "result": "BYE",
        "bye": true
      }
    ]
  },
  "message": null
}
```

With an odd number of players, the last pairing is a **bye** (`bye: true`, `blackPlayerId: null`, `result: "BYE"` already set — it does not accept result reporting).

**b) Automatic finish** → `200 OK`

When no rematch-free pairing is possible, the tournament is finished and persisted as `FINISHED`:

```json
{
  "tournamentStatus": "FINISHED",
  "round": null,
  "message": "Tournament finished automatically: no rematch-free pairing is possible"
}
```

## 8. List rounds

`GET /api/tournaments/{tournamentId}/rounds` → `200 OK`

```bash
curl http://localhost:8080/api/tournaments/{tournamentId}/rounds
```

**Response:** array of rounds (same format as the `round` object above).

## 9. Get round

`GET /api/tournaments/{tournamentId}/rounds/{roundNumber}` → `200 OK`

`roundNumber` is the sequential round number (1, 2, 3...).

```bash
curl http://localhost:8080/api/tournaments/{tournamentId}/rounds/1
```

## 10. Report result

`PUT /api/tournaments/{tournamentId}/pairings/{pairingId}/result` → `200 OK`

Only accepts pairings from the **current round** (the last one generated). The result can be corrected as long as the next round has not been generated. Byes do not accept result reporting.

**Request:**

| Field | Type | Accepted values |
|---|---|---|
| `result` | string | `WHITE_WINS`, `BLACK_WINS`, `DRAW`, `WHITE_WINS_FORFEIT`, `BLACK_WINS_FORFEIT`, `DOUBLE_FORFEIT` |

Forfeit results score 1–0 (or 0–0 for a double forfeit) but the game
counts as **unplayed**: it does not enter the color history, the winner
becomes ineligible for a bye, and tie-breaks use the virtual opponent.

```bash
curl -X PUT http://localhost:8080/api/tournaments/{tournamentId}/pairings/{pairingId}/result \
  -H 'Content-Type: application/json' \
  -d '{"result": "WHITE_WINS"}'
```

**Response:** the updated pairing:

```json
{
  "id": "d94f0162-bbb7-476b-8319-f6ca4cde7a88",
  "board": 1,
  "whitePlayerId": "73493154-3a5d-4736-b7cb-00b7b1a7c194",
  "whitePlayerName": "Alice",
  "blackPlayerId": "bf18e451-fbda-4740-ba1e-80118385c881",
  "blackPlayerName": "Carla",
  "result": "WHITE_WINS",
  "bye": false
}
```

When the last result of the last round is reported, the tournament automatically becomes `FINISHED`.

## 11. Standings

`GET /api/tournaments/{tournamentId}/standings` → `200 OK`

Ordered by score, then direct encounter, Buchholz, median Buchholz,
Sonneborn-Berger, number of wins and rating. Unplayed games use the FIDE
virtual opponent in the Buchholz family. Available at any moment
(partial while the tournament is running).

```bash
curl http://localhost:8080/api/tournaments/{tournamentId}/standings
```

**Response:**

```json
[
  {
    "rank": 1,
    "playerId": "73493154-3a5d-4736-b7cb-00b7b1a7c194",
    "name": "Alice",
    "rating": 2100,
    "score": 2.0,
    "buchholz": 3.5,
    "medianBuchholz": 2.0,
    "sonnebornBerger": 2.75,
    "wins": 2,
    "active": true
  },
  {
    "rank": 2,
    "playerId": "d4dce328-70d3-42a4-899e-694d2b2261a3",
    "name": "Bruno",
    "rating": 1950,
    "score": 1.5,
    "buchholz": 3.0,
    "medianBuchholz": 1.5,
    "sonnebornBerger": 2.25,
    "wins": 1,
    "active": true
  }
]
```

---

## Full example flow

```bash
BASE=http://localhost:8080/api/tournaments

# Create a 3-round tournament
TID=$(curl -s -X POST $BASE -H 'Content-Type: application/json' \
  -d '{"name":"Test Open","totalRounds":3}' | jq -r .id)

# Register 5 players (odd count: there will be a bye)
for p in '{"name":"Alice","rating":2100}' '{"name":"Bruno","rating":1950}' \
         '{"name":"Carla","rating":1800}' '{"name":"Diego","rating":1650}' \
         '{"name":"Elisa","rating":1500}'; do
  curl -s -X POST $BASE/$TID/players -H 'Content-Type: application/json' -d "$p" > /dev/null
done

# Start and generate round 1
curl -s -X POST $BASE/$TID/start > /dev/null
curl -s -X POST $BASE/$TID/rounds | jq .

# Report pairing results (ids come from the response above)
curl -s -X PUT $BASE/$TID/pairings/<pairingId>/result \
  -H 'Content-Type: application/json' -d '{"result":"WHITE_WINS"}' | jq .

# Generate round 2 (no rematches, bye reassigned) and check the standings
curl -s -X POST $BASE/$TID/rounds | jq .
curl -s $BASE/$TID/standings | jq .
```

## Database

Flyway migrations in `src/main/resources/db/migration/`. Schema:

- `tournaments` — tournament (`status`: `REGISTRATION` | `IN_PROGRESS` | `FINISHED`)
- `players` — tournament players
- `rounds` — rounds (`round_number` unique per tournament)
- `pairings` — pairings per round (`black_player_id` null = bye; `result`: `PENDING` | `WHITE_WINS` | `BLACK_WINS` | `DRAW` | `BYE` | `WHITE_WINS_FORFEIT` | `BLACK_WINS_FORFEIT` | `DOUBLE_FORFEIT`)

Default connection (configurable in `application.properties`): `jdbc:postgresql://localhost:5432/chess_tournament`, user/password `chess`/`chess`.
