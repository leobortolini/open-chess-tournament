# Open Chess Tournament

Backend for managing chess tournaments with **Swiss-system** pairing. Exposed as a REST API.

## Stack

- Java 25 / Spring Boot 4.1
- PostgreSQL 16 (via Docker Compose)
- Flyway for database versioning
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
| Domain | `domain` | `Tournament` aggregate (root) with `Player`, `Round` and `Pairing`; `SwissPairingEngine` domain service (pure, framework-free); exceptions and repository interface |
| Application | `application` | `TournamentService` (use cases, transactions) and output DTOs |
| Infrastructure | `infrastructure` | Spring Data JPA repository and bean configuration |
| Interface | `interfaces/rest` | REST controllers, validated input DTOs and global error handler |

All business rules live in the domain. Scores and standings are **computed** from pairing results (never stored), eliminating any risk of inconsistency.

## Swiss pairing rules

- Players are ranked by **score**, then by **rating**.
- Within each score group, the top half plays the bottom half (*fold pairing*: 1st vs middle+1st).
- **Rematches never happen**: the engine uses backtracking to find a combination where nobody meets a previous opponent. If no such combination exists, the tournament is **finished automatically**.
- With an odd number of players, the **bye** (worth 1 point) goes to the lowest-ranked player who has not received one yet. If that choice makes a rematch-free pairing impossible, other bye candidates are tried.
- **Colors**: white goes to the player with fewer white games in their history; on ties, it alternates across boards.
- Win = 1 point, draw = 0.5, loss = 0. Standings tiebreaks: **Buchholz** (sum of the scores of opponents faced), then rating.

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
| `result` | string | `WHITE_WINS`, `BLACK_WINS`, `DRAW` |

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

Ordered by score, then Buchholz, then rating. Available at any moment (partial while the tournament is running).

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
    "active": true
  },
  {
    "rank": 2,
    "playerId": "d4dce328-70d3-42a4-899e-694d2b2261a3",
    "name": "Bruno",
    "rating": 1950,
    "score": 1.5,
    "buchholz": 3.0,
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
- `pairings` — pairings per round (`black_player_id` null = bye; `result`: `PENDING` | `WHITE_WINS` | `BLACK_WINS` | `DRAW` | `BYE`)

Default connection (configurable in `application.properties`): `jdbc:postgresql://localhost:5432/chess_tournament`, user/password `chess`/`chess`.
