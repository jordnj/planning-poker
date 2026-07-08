package com.planningpoker.dto;

import java.time.Instant;

/** Consistent error body across REST and STOMP error queue (§8). */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String traceId
) {
}
