package com.planningpoker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window in-memory rate limiter guarding session creation and join
 * attempts against abuse / code enumeration (SEC-7, NFR-10).
 * For multi-instance deployments swap for a shared store (e.g. Redis).
 *
 * Client addresses are never retained. Each bucket is keyed by
 * HMAC-SHA256(per-process salt, address), truncated, so the in-memory map
 * cannot be read back to recover who contacted the service or link a person
 * to a session. The salt is random per process and never persisted, so the
 * mapping is not reproducible even by the operator across a restart; buckets
 * simply start empty, which is harmless for a one-minute window.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long epochMinute, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AppProperties props;
    private final byte[] salt = new byte[32];

    public RateLimitFilter(AppProperties props) {
        this.props = props;
        new SecureRandom().nextBytes(salt);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        Integer limit = null;
        String bucket = null;
        if ("POST".equals(method) && "/api/sessions".equals(path)) {
            limit = props.rateLimit().sessionCreatePerMinute();
            bucket = "create";
        } else if ("POST".equals(method) && path.matches("/api/sessions/[^/]+/participants")) {
            limit = props.rateLimit().joinPerMinute();
            bucket = "join";
        }

        if (limit != null) {
            String key = bucket + ":" + pseudonym(clientIp(request));
            long minute = Instant.now().getEpochSecond() / 60;
            Window w = windows.compute(key, (k, existing) ->
                    existing == null || existing.epochMinute() != minute
                            ? new Window(minute, new AtomicInteger())
                            : existing);
            if (w.count().incrementAndGet() > limit) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":429,\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
                return;
            }
            if (windows.size() > 10_000) {
                windows.entrySet().removeIf(e -> e.getValue().epochMinute() != minute);
            }
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }

    /**
     * Irreversible, per-process bucket identifier for an address. Package
     * visible so a test can assert the raw address never appears.
     */
    String pseudonym(String address) {
        if (address == null) {
            return "unknown";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] digest = mac.doFinal(address.getBytes(StandardCharsets.UTF_8));
            // 16 bytes is ample to keep buckets distinct without storing more
            // of the fingerprint than the limiter needs
            return Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(digest, 16));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive rate-limit bucket", e);
        }
    }
}
