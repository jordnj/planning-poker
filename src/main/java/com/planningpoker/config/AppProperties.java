package com.planningpoker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        String baseUrl,
        Session session,
        Token token,
        RateLimit rateLimit
) {
    public record Session(Duration idleTimeout, long cleanupIntervalMs) {
    }

    public record Token(Duration participantTtl, Duration userTtl) {
    }

    public record RateLimit(int sessionCreatePerMinute, int joinPerMinute) {
    }
}
