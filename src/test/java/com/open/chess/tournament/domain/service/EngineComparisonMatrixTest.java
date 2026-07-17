package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
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
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Four-way comparison of every pairing engine available to the project on
 * identical positions: the two in-process engines
 * ({@link LiteSwissPairingEngine} and {@link SwissPairingEngine}) and the
 * two external FIDE references, JaVaFo and bbpPairings.
 *
 * <p>Over simulated tournaments of {@value #PLAYERS} players (with draws,
 * decisive games and occasional forfeits) each round is paired by all four
 * engines on the exact same position — the tournament trajectory is driven
 * by the bracket {@link SwissPairingEngine}, and every engine is asked to
 * pair the position it reaches. A pairwise agreement matrix is printed: for
 * every pair of engines, how often the whole round is identical and how
 * many individual boards coincide.
 *
 * <p>The first round (pure fold with alternating colors) must be identical
 * across all four, and every engine must pair every position (an even field
 * of {@value #PLAYERS} never needs a bye). Later-round agreement is reported
 * rather than asserted: the Dutch rules leave tie-breaking choices
 * (transpositions, exchanges) that only a bit-exact reimplementation
 * reproduces, so equally-valid engines still diverge on which players fill
 * equivalent slots.
 *
 * <p>Requires tools/javafo/javafo.jar and tools/bbppairings/bbpPairings.exe;
 * the test is skipped when either is absent.
 *
 * <p>Tagged {@code external} because it spawns JaVaFo and bbpPairings
 * hundreds of times; excluded from the default build and run with
 * {@code mvn test -Pexternal}.
 */
@Tag("external")
class EngineComparisonMatrixTest {

    private static final Path JAVAFO_JAR = Path.of("tools", "javafo", "javafo.jar");
    private static final Path BBP_EXECUTABLE = Path.of("tools", "bbppairings", "bbpPairings.exe");
    private static final int TOURNAMENTS = 100;
    private static final int PLAYERS = 64;
    private static final int ROUNDS = 5;

    private static final int LITE = 0;
    private static final int SWISS = 1;
    private static final int JAVAFO = 2;
    private static final int BBP = 3;
    private static final String[] NAMES = {"Lite", "Swiss", "JaVaFo", "bbp"};

    private final TrfExporter exporter = new TrfExporter();

    @Test
    void allFourEnginesComparedOnIdenticalPositions() throws Exception {
        assumeTrue(Files.exists(JAVAFO_JAR), JAVAFO_JAR + " not present; skipping");
        assumeTrue(Files.exists(BBP_EXECUTABLE), BBP_EXECUTABLE + " not present; skipping");

        int[][] roundsIdentical = new int[4][4];
        long[][] pairsIdentical = new long[4][4];
        int roundsCompared = 0;
        long pairsPerEngineTotal = 0;
        int firstRoundDivergences = 0;
        int[] failures = new int[4];

        for (int seed = 1; seed <= TOURNAMENTS; seed++) {
            Tournament tournament = Tournament.create("Sim" + seed, ROUNDS);
            for (int i = 0; i < PLAYERS; i++) {
                tournament.registerPlayer("Player" + (i + 1), 2600 - i * 13);
            }
            tournament.start();
            Map<UUID, Integer> ranks = startRanks(tournament);
            Random random = new Random(seed * 1000L);

            for (int r = 1; r <= ROUNDS; r++) {
                String trf = exporter.export(tournament);

                // Drive the trajectory with the bracket engine while capturing
                // the candidates every engine will be scored on.
                CapturingEngine driver = new CapturingEngine(new SwissPairingEngine());
                Optional<Round> generated = tournament.generateNextRound(driver);
                if (generated.isEmpty()) {
                    failures[SWISS]++;
                    break;
                }
                Round swissRound = generated.get();
                List<PairingCandidate> candidates = driver.captured;

                @SuppressWarnings("unchecked")
                Set<String>[] pairs = new Set[4];
                pairs[SWISS] = pairsOfRound(swissRound, ranks);
                pairs[LITE] = pairsOfEngine(new LiteSwissPairingEngine(), candidates, ranks, failures, LITE);
                pairs[JAVAFO] = pairsOfExternal(runJavafo(trf, seed, r));
                pairs[BBP] = pairsOfExternal(runBbpPairings(trf, seed, r));

                roundsCompared++;
                pairsPerEngineTotal += pairs[BBP].size();
                for (int a = 0; a < 4; a++) {
                    for (int b = a + 1; b < 4; b++) {
                        if (pairs[a].equals(pairs[b])) {
                            roundsIdentical[a][b]++;
                        }
                        pairsIdentical[a][b] += intersectionSize(pairs[a], pairs[b]);
                    }
                }
                if (r == 1 && !allEqual(pairs)) {
                    firstRoundDivergences++;
                }

                for (Pairing pairing : swissRound.getPairings()) {
                    if (!pairing.isBye()) {
                        tournament.reportResult(pairing.getId(), randomResult(random));
                    }
                }
            }
        }

        int totalRounds = roundsCompared;
        long totalPairs = pairsPerEngineTotal;
        printMatrix("rounds identical", totalRounds,
                (a, b) -> String.format("%3.0f%%", 100.0 * roundsIdentical[a][b] / totalRounds));
        printMatrix("individual boards identical", totalRounds,
                (a, b) -> String.format("%3.0f%%", 100.0 * pairsIdentical[a][b] / totalPairs));

        for (int e = 0; e < 4; e++) {
            assertEquals(0, failures[e], NAMES[e] + " failed to pair a position all others paired");
        }
        assertEquals(0, firstRoundDivergences,
                "First-round pairings must be identical across all four engines");
    }

    private interface CellFormatter {
        String format(int a, int b);
    }

    private void printMatrix(String label, int roundsCompared, CellFormatter cell) {
        System.out.printf("%n=== %s over %d rounds (%d players) ===%n", label, roundsCompared, PLAYERS);
        System.out.printf("%-8s", "");
        for (String name : NAMES) {
            System.out.printf("%8s", name);
        }
        System.out.println();
        for (int a = 0; a < 4; a++) {
            System.out.printf("%-8s", NAMES[a]);
            for (int b = 0; b < 4; b++) {
                if (a == b) {
                    System.out.printf("%8s", "-");
                } else {
                    int lo = Math.min(a, b);
                    int hi = Math.max(a, b);
                    System.out.printf("%8s", cell.format(lo, hi));
                }
            }
            System.out.println();
        }
    }

    private boolean allEqual(Set<String>[] pairs) {
        for (int i = 1; i < pairs.length; i++) {
            if (!pairs[0].equals(pairs[i])) {
                return false;
            }
        }
        return true;
    }

    private long intersectionSize(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).count();
    }

    private Set<String> pairsOfRound(Round round, Map<UUID, Integer> ranks) {
        Set<String> pairs = new TreeSet<>();
        for (Pairing pairing : round.getPairings()) {
            if (pairing.isBye()) {
                pairs.add("bye-" + ranks.get(pairing.getWhitePlayerId()));
            } else {
                int white = ranks.get(pairing.getWhitePlayerId());
                int black = ranks.get(pairing.getBlackPlayerId());
                pairs.add(Math.min(white, black) + "-" + Math.max(white, black));
            }
        }
        return pairs;
    }

    private Set<String> pairsOfEngine(PairingEngine engine, List<PairingCandidate> candidates,
                                      Map<UUID, Integer> ranks, int[] failures, int engineIndex) {
        try {
            PairingPlan plan = engine.generate(candidates);
            Set<String> pairs = new TreeSet<>();
            for (PairingPlan.Board board : plan.boards()) {
                int white = ranks.get(board.whitePlayerId());
                int black = ranks.get(board.blackPlayerId());
                pairs.add(Math.min(white, black) + "-" + Math.max(white, black));
            }
            if (plan.byePlayerId() != null) {
                pairs.add("bye-" + ranks.get(plan.byePlayerId()));
            }
            return pairs;
        } catch (NoPairingPossibleException noRound) {
            failures[engineIndex]++;
            return new TreeSet<>();
        }
    }

    private Set<String> pairsOfExternal(List<int[]> external) {
        Set<String> pairs = new TreeSet<>();
        for (int[] pair : external) {
            if (pair[1] == 0) {
                pairs.add("bye-" + pair[0]);
            } else {
                pairs.add(Math.min(pair[0], pair[1]) + "-" + Math.max(pair[0], pair[1]));
            }
        }
        return pairs;
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
            return readPairs(output);
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    /** Runs bbpPairings on a TRF and returns [white, black] pairs; the bye comes back as [player, 0]. */
    private List<int[]> runBbpPairings(String trf, int seed, int round) throws IOException, InterruptedException {
        Path input = Files.createTempFile("bbp-sim" + seed + "-r" + round, ".trfx");
        Path output = Files.createTempFile("bbp-sim" + seed + "-r" + round, ".out");
        try {
            Files.writeString(input, trf);
            Process process = new ProcessBuilder(
                    BBP_EXECUTABLE.toAbsolutePath().toString(), "--dutch",
                    input.toAbsolutePath().toString(),
                    "-p", output.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String log = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                fail("bbpPairings failed on sim " + seed + " round " + round + ":\n" + log + "\nTRF:\n" + trf);
            }
            return readPairs(output);
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    private List<int[]> readPairs(Path output) throws IOException {
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
                "Pairing tool output header does not match its pair count");
        return pairs;
    }
}
