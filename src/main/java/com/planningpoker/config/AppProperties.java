package com.planningpoker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        /** Base64-encoded 32-byte AES key for at-rest column encryption. */
        String dataKey,
        String baseUrl,
        Session session,
        Token token,
        RateLimit rateLimit,
        Actuator actuator
) {
    public record Session(Duration idleTimeout, long cleanupIntervalMs) {
    }

    public record Token(Duration participantTtl, Duration userTtl) {
    }

    public record RateLimit(int sessionCreatePerMinute, int joinPerMinute) {
    }

    /** Operator credential for the non-public actuator endpoints. */
    public record Actuator(String user, String password) {
    }
}
