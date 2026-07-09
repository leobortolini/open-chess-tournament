package com.open.chess.tournament.infrastructure.config;

import com.open.chess.tournament.domain.service.SwissPairingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    public SwissPairingEngine swissPairingEngine() {
        return new SwissPairingEngine();
    }
}
