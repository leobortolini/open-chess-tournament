package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.model.GameResult;
import com.open.chess.tournament.domain.model.Pairing;
import com.open.chess.tournament.domain.model.Player;
import com.open.chess.tournament.domain.model.Round;
import com.open.chess.tournament.domain.model.Tournament;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Temporary debugging harness: replays one simulated tournament from
 * BbpPairingsComparisonTest and dumps player state, our pairing and
 * bbpPairings' pairing for a chosen round. Run with e.g.
 * -Ddebug.seed=1 -Ddebug.round=4
 */
class DebugSimTest {

    private static final Path BBP_EXECUTABLE = Path.of("tools", "bbppairings", "bbpPairings.exe");
    private static final int ROUNDS = 5;

    private final TrfExporter exporter = new TrfExporter();

    @Test
    void replay() throws Exception {
        int seed = Integer.getInteger("debug.seed", 1);
        int targetRound = Integer.getInteger("debug.round", 4);

        int playerCount = 8 + (seed % 9);
        Tournament tournament = Tournament.create("Sim" + seed, ROUNDS);
        for (int i = 0; i < playerCount; i++) {
            tournament.registerPlayer("Player" + (i + 1), 2600 - i * 13);
        }
        tournament.start();
        Map<UUID, Integer> ranks = startRanks(tournament);
        Random random = new Random(seed * 1000L);
        PairingEngine engine = new SwissPairingEngine();

        for (int r = 1; r <= targetRound; r++) {
            String trf = exporter.export(tournament);
            List<int[]> bbp = runBbpPairings(trf, seed, r);
            if (r == targetRound) {
                System.out.println("=== TRF before round " + r + " ===");
                System.out.println(trf);
                System.out.println("=== Candidates before round " + r + " ===");
                List<PairingCandidate> candidates = buildCandidates(tournament);
                candidates.sort(Comparator
                        .comparingDouble(PairingCandidate::score).reversed()
                        .thenComparing(Comparator.comparingInt(PairingCandidate::rating).reversed())
                        .thenComparing(c -> c.playerId().toString()));
                for (PairingCandidate c : candidates) {
                    List<Integer> opponents = c.previousOpponents().stream()
                            .map(ranks::get).sorted().toList();
                    System.out.printf(
                            "rank=%2d score=%.1f pref=%+d abs=%b strong=%b bal=%+d last=%+d prev=%+d "
                                    + "dn1=%b up1=%b dn2=%b up2=%b played=%d hadBye=%b forfeitW=%b opps=%s%n",
                            ranks.get(c.playerId()), c.score(), c.colorPreference(),
                            c.hasAbsoluteColorPreference(), c.hasStrongColorPreference(),
                            c.colorBalance(), c.lastColor(), c.previousColor(),
                            c.floatedDownLastRound(), c.floatedUpLastRound(),
                            c.floatedDownTwoRoundsAgo(), c.floatedUpTwoRoundsAgo(),
                            c.whiteGames() + c.blackGames(),
                            c.hadBye(), c.forfeitWin(), opponents);
                }
            }
            Optional<Round> generated = tournament.generateNextRound(engine);
            Round round = generated.orElseThrow();
            if (r == targetRound) {
                System.out.println("=== ours (white v black, board order) ===");
                for (Pairing pairing : round.getPairings()) {
                    if (pairing.isBye()) {
                        System.out.println("bye: " + ranks.get(pairing.getWhitePlayerId()));
                    } else {
                        System.out.println(ranks.get(pairing.getWhitePlayerId())
                                + " v " + ranks.get(pairing.getBlackPlayerId()));
                    }
                }
                System.out.println("=== bbp (white v black, board order) ===");
                for (int[] pair : bbp) {
                    if (pair[1] == 0) {
                        System.out.println("bye: " + pair[0]);
                    } else {
                        System.out.println(pair[0] + " v " + pair[1]);
                    }
                }
                return;
            }
            for (Pairing pairing : round.getPairings()) {
                if (!pairing.isBye()) {
                    tournament.reportResult(pairing.getId(), randomResult(random));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<PairingCandidate> buildCandidates(Tournament tournament) throws Exception {
        Method method = Tournament.class.getDeclaredMethod("buildCandidates");
        method.setAccessible(true);
        return new ArrayList<>((List<PairingCandidate>) method.invoke(tournament));
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

    private List<int[]> runBbpPairings(String trf, int seed, int round)
            throws IOException, InterruptedException {
        Path input = Files.createTempFile("bbp-dbg" + seed + "-r" + round, ".trfx");
        Path output = Files.createTempFile("bbp-dbg" + seed + "-r" + round, ".out");
        try {
            Files.writeString(input, trf);
            String executable = BBP_EXECUTABLE.toAbsolutePath().toString();
            Process process = new ProcessBuilder(
                    executable, "--dutch",
                    input.toAbsolutePath().toString(),
                    "-p", output.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            process.waitFor(30, TimeUnit.SECONDS);
            List<String> lines = Files.readAllLines(output);
            List<int[]> pairs = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                pairs.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
            }
            return pairs;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }
}
