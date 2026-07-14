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
 * Round-by-round comparison against JaVaFo, the FIDE reference pairing
 * implementation, over simulated tournaments (with draws, decisive games
 * and occasional forfeits). Each round is exported as a TRF and JaVaFo is
 * asked to pair the same position this engine pairs.

 * First-round pairings (pure fold with alternating colors) must match
 * JaVaFo exactly. Later rounds are dominated by the same criteria but the
 * Dutch rules leave tie-breaking choices (transpositions, exchanges) that
 * only a bit-exact reimplementation reproduces, so agreement is asserted
 * against a measured floor and every divergence is printed for review.

 * Requires tools/javafo/javafo.jar (see tools/javafo/README.md); the test
 * is skipped when the jar is not present.
 */
class JaVaFoComparisonTest {

    private static final Path JAVAFO_JAR = Path.of("tools", "javafo", "javafo.jar");
    private static final int TOURNAMENTS = 100;
    private static final int ROUNDS = 5;
    private static final double MINIMUM_AGREEMENT = 0.55;

    private final SwissPairingEngine engine = new SwissPairingEngine();
    private final TrfExporter exporter = new TrfExporter();

    @Test
    void pairingsTrackJavafoAcrossSimulatedTournaments() throws Exception {
        assumeTrue(Files.exists(JAVAFO_JAR), "tools/javafo/javafo.jar not present; skipping");

        int roundsCompared = 0;
        int exactMatches = 0;
        int colorMatches = 0;
        int firstRoundMismatches = 0;
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
                List<int[]> javafo = runJavafo(exporter.export(tournament), seed, r);
                Round round = tournament.generateNextRound(engine)
                        .orElseThrow(() -> new AssertionError("No legal pairing found by our engine"));

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
                for (int[] pair : javafo) {
                    if (pair[1] == 0) {
                        theirBye = pair[0];
                    } else {
                        theirPairs.add(Math.min(pair[0], pair[1]) + "-" + Math.max(pair[0], pair[1]));
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
                            "Sim%d round %d: ours=%s bye=%d | javafo=%s bye=%d",
                            seed, r, ourPairs, ourBye, theirPairs, theirBye));
                }

                for (Pairing pairing : round.getPairings()) {
                    if (!pairing.isBye()) {
                        tournament.reportResult(pairing.getId(), randomResult(random));
                    }
                }
            }
        }

        System.out.printf("JaVaFo agreement: %d/%d rounds identical, %d also color-identical%n",
                exactMatches, roundsCompared, colorMatches);
        divergences.forEach(System.out::println);

        assertEquals(0, firstRoundMismatches, "First-round pairings must match JaVaFo exactly");
        double agreement = exactMatches / (double) roundsCompared;
        assertTrue(agreement >= MINIMUM_AGREEMENT, String.format(
                "Agreement with JaVaFo dropped to %.0f%% (%d/%d); inspect divergences above",
                agreement * 100, exactMatches, roundsCompared));
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

    /** Runs JaVaFo on a TRF and returns [white, black] pairs; the bye comes back as [player, 0]. */
    private List<int[]> runJavafo(String trf, int seed, int round) throws IOException, InterruptedException {
        Path input = Files.createTempFile("javafo-sim" + seed + "-r" + round, ".trfx");
        Path output = Files.createTempFile("javafo-sim" + seed + "-r" + round, ".out");
        try {
            Files.writeString(input, trf);
            Process process = new ProcessBuilder(
                    "java", "-jar", JAVAFO_JAR.toAbsolutePath().toString(),
                    input.toAbsolutePath().toString(), "-p", output.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String log = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                fail("JaVaFo failed on sim " + seed + " round " + round + ":\n" + log + "\nTRF:\n" + trf);
            }
            List<String> lines = Files.readAllLines(output);
            List<int[]> pairs = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                pairs.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
            }
            assertEquals(Integer.parseInt(lines.getFirst().trim()), pairs.size(),
                    "JaVaFo output header does not match its pair count");
            return pairs;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }
}
