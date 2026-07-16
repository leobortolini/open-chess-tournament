package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.model.GameResult;
import com.open.chess.tournament.domain.model.Pairing;
import com.open.chess.tournament.domain.model.Player;
import com.open.chess.tournament.domain.model.Round;
import com.open.chess.tournament.domain.model.Tournament;
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
 * {@link LiteSwissPairingEngine} shares the weighted-matching architecture
 * with bbpPairings, so agreement is expected to be high. The
 * {@link SwissPairingEngine} follows the literal Dutch bracket
 * procedure and may diverge on tie-breaking choices.
 *
 * First-round pairings (pure fold with alternating colors) must match
 * bbpPairings exactly. Later rounds are dominated by the same criteria
 * but implementation differences leave tie-breaking choices that only a
 * bit-exact reimplementation reproduces, so agreement is asserted against
 * a measured floor and every divergence is printed for review.
 *
 * Requires tools/bbppairings/bbpPairings.exe (see
 * tools/bbppairings/README.md); the test is skipped when the executable
 * is not present.
 */
class BbpPairingsComparisonTest {

    private static final Path BBP_EXECUTABLE = Path.of("tools", "bbppairings", "bbpPairings.exe");
    private static final int TOURNAMENTS = 100;
    private static final int ROUNDS = 5;
    private static final double MINIMUM_AGREEMENT = 0.55;

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
        double rate = agreement.exact / (double) agreement.rounds;
        assertTrue(rate >= MINIMUM_AGREEMENT, String.format(
                "Agreement with bbpPairings dropped to %.0f%% (%d/%d); inspect divergences above",
                rate * 100, agreement.exact, agreement.rounds));
    }

    @Test
    void bothEnginesComparedAgainstBbpPairings() throws Exception {
        assumeTrue(Files.exists(BBP_EXECUTABLE), BBP_EXECUTABLE + " not present; skipping");

        Agreement global = measure(new SwissPairingEngine(), false);
        Agreement lite = measure(new LiteSwissPairingEngine(), false);

        System.out.printf("%n=== bbpPairings comparison over %d rounds ===%n", global.rounds);
        System.out.printf("  global matching : %3d/%d identical (%.0f%%), %d color-identical, %d unpairable%n",
                global.exact, global.rounds, 100.0 * global.exact / global.rounds,
                global.colorExact, global.failures);
        System.out.printf("  lite engine  : %3d/%d identical (%.0f%%), %d color-identical, %d unpairable%n",
                lite.exact, lite.rounds, 100.0 * lite.exact / lite.rounds,
                lite.colorExact, lite.failures);

        assertEquals(0, lite.failures,
                "The completion look-ahead must pair every position bbpPairings pairs");
        assertEquals(0, lite.firstRoundMismatches,
                "First-round pairings must match bbpPairings exactly");
    }

    private record Agreement(int rounds, int exact, int colorExact,
                             int firstRoundMismatches, int failures) {
    }

    private Agreement measure(PairingEngine engine, boolean printDivergences) throws Exception {
        int roundsCompared = 0;
        int exactMatches = 0;
        int colorMatches = 0;
        int firstRoundMismatches = 0;
        int failures = 0;
        List<String> divergences = new ArrayList<>();

        for (int seed = 1; seed <= TOURNAMENTS; seed++) {
            int playerCount = 8 + (seed % 9);
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
            System.out.printf("bbpPairings agreement: %d/%d rounds identical, %d also color-identical%n",
                    exactMatches, roundsCompared, colorMatches);
            divergences.forEach(System.out::println);
        }
        return new Agreement(roundsCompared, exactMatches, colorMatches,
                firstRoundMismatches, failures);
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
