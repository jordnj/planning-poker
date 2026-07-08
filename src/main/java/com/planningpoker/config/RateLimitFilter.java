package com.planningpoker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window in-memory rate limiter guarding session creation and join
 * attempts against abuse / code enumeration (SEC-7, NFR-10).
 * For multi-instance deployments swap for a shared store (e.g. Redis).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long epochMinute, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AppProperties props;

    public RateLimitFilter(AppProperties props) {
        this.props = props;
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
            String key = bucket + ":" + clientIp(request);
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
}
