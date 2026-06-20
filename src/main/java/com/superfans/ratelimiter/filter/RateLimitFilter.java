package com.superfans.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superfans.ratelimiter.model.ErrorResponse;
import com.superfans.ratelimiter.model.RateLimitResult;
import com.superfans.ratelimiter.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Servlet filter that enforces per-user rate limits after successful JWT authentication.
 *
 * <h2>Order</h2>
 * <p>Runs after {@link JwtAuthenticationFilter}. By this point, the
 * {@link SecurityContextHolder} is populated with an authenticated principal.
 *
 * <h2>Response Headers</h2>
 * <p>Every response (allowed or denied) includes the standard rate-limit headers:
 * <ul>
 *   <li>{@code X-RateLimit-Limit}     — configured max requests per window</li>
 *   <li>{@code X-RateLimit-Remaining} — requests left before throttling</li>
 *   <li>{@code X-RateLimit-Reset}     — epoch seconds when the window resets</li>
 * </ul>
 *
 * <p>When the limit is exceeded, an additional {@code Retry-After} header
 * (seconds) is added alongside a 429 JSON body.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    // Standard rate-limit headers
    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String userId = resolveUserId(request);

        if (userId == null) {
            // JWT filter would have already rejected the request. So this won't happen
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitResult result = rateLimitService.check(userId);

        // Calculate reset epoch (current time + TTL)
        long resetEpoch = System.currentTimeMillis() / 1_000 + result.getRetryAfterSeconds();

        // Always set rate-limit headers regardless of allow/deny
        response.setHeader(HEADER_LIMIT, String.valueOf(result.getLimit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(result.getRemaining()));
        response.setHeader(HEADER_RESET, String.valueOf(resetEpoch));

        if (result.isAllowed()) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded — userId={}, count={}/{}", userId,
                    result.getCurrentCount(), result.getLimit());
            sendTooManyRequests(response, result);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the authenticated user ID.
     * Primary source: request attribute set by {@link JwtAuthenticationFilter}.
     * Fallback: SecurityContext principal name.
     */
    private String resolveUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE);
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
    }

    private void sendTooManyRequests(HttpServletResponse response, RateLimitResult result)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(result.getRetryAfterSeconds()));

        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .message(String.format(
                        "Rate limit exceeded. You have made %d requests (limit: %d). "
                                + "Retry after %d seconds.",
                        result.getCurrentCount(),
                        result.getLimit(),
                        result.getRetryAfterSeconds()))
                .timestamp(LocalDateTime.now())
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
