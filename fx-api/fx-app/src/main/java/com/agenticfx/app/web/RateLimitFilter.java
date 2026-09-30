package com.agenticfx.app.web;

import com.agenticfx.app.config.AgenticFxProperties;
import com.agenticfx.app.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Fixed-window rate limit per authenticated subject (falls back to client IP), backed by Redis so it holds
 * across instances. Fails open if Redis is unavailable: availability of reads beats strict limiting in an MVP,
 * and every money-moving path is independently protected by policy, idempotency and database constraints.
 * Runs after Spring Security so the subject is known.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate redis;
    private final int limit;

    public RateLimitFilter(StringRedisTemplate redis, AgenticFxProperties properties) {
        this.redis = redis;
        this.limit = properties.security().requestsPerMinute();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String subject = CurrentUser.subjectFromContext().orElse("ip:" + request.getRemoteAddr());
        long window = System.currentTimeMillis() / 60_000;
        String key = "ratelimit:" + subject + ":" + window;
        Long count = null;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofMinutes(2));
            }
        } catch (RuntimeException redisDown) {
            log.debug("Rate limiter unavailable, allowing request: {}", redisDown.getMessage());
        }
        if (count != null && count > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,\"code\":\"RATE_LIMITED\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
