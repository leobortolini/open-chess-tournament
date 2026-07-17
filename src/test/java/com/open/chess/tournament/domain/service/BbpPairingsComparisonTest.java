package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.model.GameResult;
import com.open.chess.tournament.domain.model.Pairing;
import com.open.chess.tournament.domain.model.Player;
import com.open.chess.tournament.domain.model.Round;
import com.open.chess.tournament.domain.model.Tournament;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-by-round comparison against bbpPairings, the FIDE-endorsed Swiss
 * pairing engine that uses weighted perfect matching (Blossom), over
 * simulated tournaments (with draws, decisive games and occasional
 * forfeits). Each round is exported as a TRF and bbpPairings is asked
 * to pair the same position these engines pair.
 *
 * {@link SwissPairingEngine} follows the literal Dutch bracket procedure
 * and tracks bbpPairings closely; {@link LiteSwissPairingEngine} trades
 * accuracy for speed and diverges more.
 *
 * First-round pairings (pure fold with alternating colors) must match
 * bbpPairings exactly. Later rounds are dominated by the same criteria
 * but implementation differences leave tie-breaking choices that only a
 * bit-exact reimplementation reproduces, so agreement is asserted against
 * a measured floor and every divergence is printed for review.
 *
 * Two agreement metrics are measured. Round-level agreement is
 * all-or-nothing: a single differing board makes the whole round count as
 * a divergence, which is severe on a {@value #PLAYERS}-player field where
 * each round has dozens of boards. Pair-level agreement counts individual
 * boards and degrades gracefully, so the lite engine is asserted against
 * it while the global engine keeps the stricter round-level floor.
 *
 * Requires tools/bbppairings/bbpPairings.exe (see
 * tools/bbppairings/README.md); the test is skipped when the executable
 * is not present.
 *
 * <p>Tagged {@code external} because it spawns bbpPairings hundreds of
 * times; excluded from the default build and run with
 * {@code mvn test -Pexternal}.
 */
@Tag("external")
class BbpPairingsComparisonTest {

    private static final Path BBP_EXECUTABLE = Path.of("tools", "bbppairings", "bbpPairings.exe");
    private static final int TOURNAMENTS = 100;
    private static final int PLAYERS = 64;
    private static final int ROUNDS = 5;
    private static final double MINIMUM_AGREEMENT = 0.55;
    private static final double MINIMUM_PAIR_AGREEMENT = 0.80;

    private final TrfExporter exporter = new TrfExporter();

    @Test
    void swissEngineTracksBbpPairingsAcrossSimulatedTournaments() throws Exception {
        assumeTrue(Files.exists(BBP_EXECUTABLE), BBP_EXECUTABLE + " not present; skipping");

        Agreement agreement = measure(new SwissPairingEngine(), true);

        assertEquals(0, agreement.firstRoundMismatches,
                "First-round pairings must match bbpPairings exactly");
        assertEquals(0, agreement.failures,
                "Every simulated round must be pairable");
        double rate = agreement.exact / (double) agreement.rounds;
        assertTrue(rate >= MINIMUM_AGREEMENT, String.format(
                "Agreement with bbpPairings dropped to %.0f%% (%d/%d); inspect divergences above",
                rate * 100, agreement.exact, agreement.rounds));
    }

    @Test
    void liteSwissEngineTracksBbpPairingsAcrossSimulatedTournaments() throws Exception {
        assumeTrue(Files.exists(BBP_EXECUTABLE), BBP_EXECUTABLE + " not present; skipping");

        Agreement agreement = measure(new LiteSwissPairingEngine(), true);

        assertEquals(0, agreement.firstRoundMismatches,
                "First-round pairings must match bbpPairings exactly");
        assertEquals(0, agreement.failures,
                "Every simulated round must be pairable");
        double rate = agreement.pairMatches / (double) agreement.pairs;
        assertTrue(rate >= MINIMUM_PAIR_AGREEMENT, String.format(
                "Pair-level agreement with bbpPairings dropped to %.0f%% (%d/%d pairs); "
                        + "inspect divergences above",
                rate * 100, agreement.pairMatches, agreement.pairs));
    }

    @Test
    void bothEnginesComparedAgainstBbpPairings() throws Exception {
        assumeTrue(Files.exists(BBP_EXECUTABLE), BBP_EXECUTABLE + " not present; skipping");

        Agreement global = measure(new SwissPairingEngine(), false);
        Agreement lite = measure(new LiteSwissPairingEngine(), false);

        System.out.printf("%n=== bbpPairings comparison over %d rounds ===%n", global.rounds);
        System.out.printf("  global matching : %3d/%d rounds identical (%.0f%%), %d/%d pairs identical (%.0f%%), "
                        + "%d color-identical, %d unpairable%n",
                global.exact, global.rounds, 100.0 * global.exact / global.rounds,
                global.pairMatches, global.pairs, 100.0 * global.pairMatches / global.pairs,
                global.colorExact, global.failures);
        System.out.printf("  lite engine  : %3d/%d rounds identical (%.0f%%), %d/%d pairs identical (%.0f%%), "
                        + "%d color-identical, %d unpairable%n",
                lite.exact, lite.rounds, 100.0 * lite.exact / lite.rounds,
                lite.pairMatches, lite.pairs, 100.0 * lite.pairMatches / lite.pairs,
                lite.colorExact, lite.failures);

        assertEquals(0, lite.failures,
                "The completion look-ahead must pair every position bbpPairings pairs");
        assertEquals(0, lite.firstRoundMismatches,
                "First-round pairings must match bbpPairings exactly");
    }

    /**
     * The strong guarantee behind the weaker {@link #MINIMUM_PAIR_AGREEMENT}
     * floor: where the lite engine pairs a bracket differently from
     * bbpPairings, it must choose a different <em>optimum</em>, never a worse
     * pairing. On the same position both pairings are scored on the FIDE
     * quality criteria in priority order and the lite pairing must never be
     * lexicographically worse:
     *   1. C.3  — pairs of players sharing an absolute color preference;
     *   2. C.6  — the sum of squared score differences (floats);
     *   3. C.8  — pairs where a color preference goes unmet.
     * A single position where the lite engine loses on a higher tier than it
     * wins on is a real regression, not a tie-break difference, and fails.
     *
     * The metrics are summed over the whole field. bbpPairings minimizes
     * C.8 bracket by bracket top-down, so a global matching can legitimately
     * reach a lower global color count while pairing an upper bracket the
     * same way; that shows up here as the lite engine being <em>better</em>,
     * which is allowed. Only the lite engine being worse fails.
     */
    @Test
    void liteEngineNeverPairsWorseThanBbpPairings() throws Exception {
        assumeTrue(Files.exists(BBP_EXECUTABLE), BBP_EXECUTABLE + " not present; skipping");

        List<String> regressions = new ArrayList<>();
        int divergent = 0;
        int strictlyBetter = 0;

        for (int seed = 1; seed <= TOURNAMENTS; seed++) {
            Tournament tournament = Tournament.create("Sim" + seed, ROUNDS);
            for (int i = 0; i < PLAYERS; i++) {
                tournament.registerPlayer("Player" + (i + 1), 2600 - i * 13);
            }
            tournament.start();
            Map<UUID, Integer> ranks = startRanks(tournament);
            Random random = new Random(seed * 1000L);

            for (int r = 1; r <= ROUNDS; r++) {
                List<int[]> bbp = runBbpPairings(exporter.export(tournament), seed, r);

                CapturingEngine capturing = new CapturingEngine(new LiteSwissPairingEngine());
                Optional<Round> generated = tournament.generateNextRound(capturing);
                if (generated.isEmpty()) {
                    break;
                }
                Round round = generated.get();

                Map<Integer, PairingCandidate> byRank = new HashMap<>();
                for (PairingCandidate candidate : capturing.captured) {
                    byRank.put(ranks.get(candidate.playerId()), candidate);
                }

                List<int[]> ourPairs = new ArrayList<>();
                Set<String> ourKeys = new TreeSet<>();
                Set<String> theirKeys = new TreeSet<>();
                for (Pairing pairing : round.getPairings()) {
                    if (!pairing.isBye()) {
                        int white = ranks.get(pairing.getWhitePlayerId());
                        int black = ranks.get(pairing.getBlackPlayerId());
                        ourPairs.add(new int[]{white, black});
                        ourKeys.add(Math.min(white, black) + "-" + Math.max(white, black));
                    }
                }
                for (int[] pair : bbp) {
                    if (pair[1] != 0) {
                        theirKeys.add(Math.min(pair[0], pair[1]) + "-" + Math.max(pair[0], pair[1]));
                    }
                }

                if (!ourKeys.equals(theirKeys)) {
                    divergent++;
                    Quality ours = quality(ourPairs, byRank);
                    Quality theirs = quality(bbp, byRank);
                    int comparison = ours.compareTo(theirs);
                    if (comparison > 0) {
                        regressions.add(String.format(
                                "Sim%d round %d: lite is worse — lite%s vs bbp%s",
                                seed, r, ours, theirs));
                    } else if (comparison < 0) {
                        strictlyBetter++;
                    }
                }

                for (Pairing pairing : round.getPairings()) {
                    if (!pairing.isBye()) {
                        tournament.reportResult(pairing.getId(), randomResult(random));
                    }
                }
            }
        }

        System.out.printf("%nlite quality vs bbpPairings: %d divergent rounds, %d where lite is a "
                        + "strictly better optimum, %d regressions%n",
                divergent, strictlyBetter, regressions.size());
        regressions.forEach(System.out::println);
        assertTrue(regressions.isEmpty(),
                regressions.size() + " round(s) where the lite engine pairs strictly worse than "
                        + "bbpPairings on a FIDE criterion; these are quality defects, not tie-breaks");
    }

    /** A pairing engine that records the candidates it was asked to pair. */
    private static final class CapturingEngine implements PairingEngine {
        private final PairingEngine delegate;
        private List<PairingCandidate> captured = List.of();

        private CapturingEngine(PairingEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public PairingPlan generate(List<PairingCandidate> candidates) {
            this.captured = candidates;
            return delegate.generate(candidates);
        }
    }

    /**
     * FIDE quality of a pairing, in descending priority. Compared
     * lexicographically: a lower absolute-color count wins outright, ties
     * break on the sum of squared score differences, then on unmet color
     * preferences.
     */
    private record Quality(int absoluteColorViolations, double scoreSquaredDiff, int colorViolations)
            implements Comparable<Quality> {

        @Override
        public int compareTo(Quality other) {
            if (absoluteColorViolations != other.absoluteColorViolations) {
                return Integer.compare(absoluteColorViolations, other.absoluteColorViolations);
            }
            if (Math.abs(scoreSquaredDiff - other.scoreSquaredDiff) > 1e-6) {
                return Double.compare(scoreSquaredDiff, other.scoreSquaredDiff);
            }
            return Integer.compare(colorViolations, other.colorViolations);
        }

        @Override
        public String toString() {
            return String.format("{absColor=%d, scoreSq=%.2f, color=%d}",
                    absoluteColorViolations, scoreSquaredDiff, colorViolations);
        }
    }

    private Quality quality(List<int[]> pairs, Map<Integer, PairingCandidate> byRank) {
        int absoluteColorViolations = 0;
        int colorViolations = 0;
        double scoreSquaredDiff = 0.0;
        for (int[] pair : pairs) {
            if (pair[1] == 0) {
                continue;
            }
            PairingCandidate a = byRank.get(pair[0]);
            PairingCandidate b = byRank.get(pair[1]);
            double diff = Math.abs(a.score() - b.score());
            scoreSquaredDiff += diff * diff;
            boolean samePreference = a.colorPreference() != PairingCandidate.NONE
                    && a.colorPreference() == b.colorPreference();
            if (samePreference) {
                colorViolations++;
                if (a.hasAbsoluteColorPreference() && b.hasAbsoluteColorPreference()) {
                    absoluteColorViolations++;
                }
            }
        }
        return new Quality(absoluteColorViolations, scoreSquaredDiff, colorViolations);
    }

    private record Agreement(int rounds, int exact, int colorExact,
                             int pairs, int pairMatches,
                             int firstRoundMismatches, int failures) {
    }

    private Agreement measure(PairingEngine engine, boolean printDivergences) throws Exception {
        int roundsCompared = 0;
        int exactMatches = 0;
        int colorMatches = 0;
        int pairsCompared = 0;
        int pairMatches = 0;
        int firstRoundMismatches = 0;
        int failures = 0;
        List<String> divergences = new ArrayList<>();

        for (int seed = 1; seed <= TOURNAMENTS; seed++) {
            int playerCount = PLAYERS;
            Tournament tournament = Tournament.create("Sim" + seed, ROUNDS);
            for (int i = 0; i < playerCount; i++) {
                tournament.registerPlayer("Player" + (i + 1), 2600 - i * 13);
            }
            tournament.start();
            Map<UUID, Integer> ranks = startRanks(tournament);
            Random random = new Random(seed * 1000L);

            for (int r = 1; r <= ROUNDS; r++) {
                List<int[]> bbp = runBbpPairings(exporter.export(tournament), seed, r);
                Optional<Round> generated = tournament.generateNextRound(engine);
                if (generated.isEmpty()) {
                    failures++;
                    roundsCompared++;
                    divergences.add(String.format(
                            "Sim%d round %d: our engine found no legal round; bbp=%s",
                            seed, r, format(bbp)));
                    break;
                }
                Round round = generated.get();

                Set<String> ourPairs = new TreeSet<>();
                Set<String> ourBoards = new TreeSet<>();
                int ourBye = 0;
                for (Pairing pairing : round.getPairings()) {
                    if (pairing.isBye()) {
                        ourBye = ranks.get(pairing.getWhitePlayerId());
                    } else {
                        int white = ranks.get(pairing.getWhitePlayerId());
                        int black = ranks.get(pairing.getBlackPlayerId());
                        ourPairs.add(Math.min(white, black) + "-" + Math.max(white, black));
                        ourBoards.add(white + "v" + black);
                    }
                }
                Set<String> theirPairs = new TreeSet<>();
                Set<String> theirBoards = new TreeSet<>();
                int theirBye = 0;
                for (int[] pair : bbp) {
                    if (pair[1] == 0) {
                        theirBye = pair[0];
                    } else {
                        theirPairs.add(Math.min(pair[0], pair[1])
                                + "-" + Math.max(pair[0], pair[1]));
                        theirBoards.add(pair[0] + "v" + pair[1]);
                    }
                }

                roundsCompared++;
                pairsCompared += theirPairs.size();
                pairMatches += (int) ourPairs.stream().filter(theirPairs::contains).count();
                boolean samePairs = ourPairs.equals(theirPairs) && ourBye == theirBye;
                if (samePairs) {
                    exactMatches++;
                    if (ourBoards.equals(theirBoards)) {
                        colorMatches++;
                    }
                } else {
                    if (r == 1) {
                        firstRoundMismatches++;
                    }
                    divergences.add(String.format(
                            "Sim%d round %d: ours=%s bye=%d | bbp=%s bye=%d",
                            seed, r, ourPairs, ourBye, theirPairs, theirBye));
                }

                for (Pairing pairing : round.getPairings()) {
                    if (!pairing.isBye()) {
                        tournament.reportResult(pairing.getId(), randomResult(random));
                    }
                }
            }
        }

        if (printDivergences) {
            System.out.printf("bbpPairings agreement: %d/%d rounds identical, %d also color-identical, "
                            + "%d/%d individual pairs identical%n",
                    exactMatches, roundsCompared, colorMatches, pairMatches, pairsCompared);
            divergences.forEach(System.out::println);
        }
        return new Agreement(roundsCompared, exactMatches, colorMatches,
                pairsCompared, pairMatches, firstRoundMismatches, failures);
    }

    private String format(List<int[]> bbp) {
        return bbp.stream()
                .map(pair -> pair[0] + "-" + pair[1])
                .sorted()
                .toList()
                .toString();
    }

    private Map<UUID, Integer> startRanks(Tournament tournament) {
        List<Player> byRank = tournament.getPlayers().stream()
                .sorted(Comparator.comparingInt(Player::getRating).reversed()
                        .thenComparing(player -> player.getId().toString()))
                .toList();
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int i = 0; i < byRank.size(); i++) {
            ranks.put(byRank.get(i).getId(), i + 1);
        }
        return ranks;
    }

    private GameResult randomResult(Random random) {
        int roll = random.nextInt(100);
        if (roll < 4) {
            return GameResult.WHITE_WINS_FORFEIT;
        }
        if (roll < 8) {
            return GameResult.BLACK_WINS_FORFEIT;
        }
        if (roll < 40) {
            return GameResult.DRAW;
        }
        if (roll < 70) {
            return GameResult.WHITE_WINS;
        }
        return GameResult.BLACK_WINS;
    }

    /**
     * Runs bbpPairings on a TRF and returns [white, black] pairs;
     * the bye comes back as [player, 0].
     */
    private List<int[]> runBbpPairings(String trf, int seed, int round)
            throws IOException, InterruptedException {
        Path input = Files.createTempFile("bbp-sim" + seed + "-r" + round, ".trfx");
        Path output = Files.createTempFile("bbp-sim" + seed + "-r" + round, ".out");
        try {
            Files.writeString(input, trf);
            String executable = BBP_EXECUTABLE.toAbsolutePath().toString();
            Process process = new ProcessBuilder(
                    executable, "--dutch",
                    input.toAbsolutePath().toString(),
                    "-p", output.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String log = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                fail("bbpPairings failed on sim " + seed + " round " + round
                        + " (exit " + process.exitValue() + "):\n" + log
                        + "\nTRF:\n" + trf);
            }
            List<String> lines = Files.readAllLines(output);
            List<int[]> pairs = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                pairs.add(new int[]{Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1])});
            }
            assertEquals(Integer.parseInt(lines.getFirst().trim()), pairs.size(),
                    "bbpPairings output header does not match its pair count");
            return pairs;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }
}
