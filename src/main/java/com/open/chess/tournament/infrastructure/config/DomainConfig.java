package com.open.chess.tournament.infrastructure.config;

import com.open.chess.tournament.domain.service.SwissPairingEngine;
import com.open.chess.tournament.domain.service.matching.BlossomVIMatchingSolver;
import com.open.chess.tournament.domain.service.matching.BlossomVMatchingSolver;
import com.open.chess.tournament.domain.service.matching.PerfectMatchingSolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    public PerfectMatchingSolver perfectMatchingSolver(@Value("${pairing.matching-algorithm:blossom-v}") String algorithm) {
        return switch (algorithm) {
            case "blossom-v" -> new BlossomVMatchingSolver();
            case "blossom-vi" -> new BlossomVIMatchingSolver();
            default -> throw new IllegalArgumentException(
                    "Unknown pairing.matching-algorithm '" + algorithm
                            + "'; expected 'blossom-v' or 'blossom-vi'");
        };
    }

    @Bean
    public SwissPairingEngine swissPairingEngine(PerfectMatchingSolver solver) {
        return new SwissPairingEngine(solver);
    }
}
