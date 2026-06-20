package com.superfans.ratelimiter.filter;

import com.superfans.ratelimiter.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that validates a JWT Bearer token on every incoming request.
 *
 * <h2>Order</h2>
 * <p>Runs before {@link RateLimitFilter} in the security filter chain.
 * The rate-limiter relies on an authenticated principal being present in
 * {@link SecurityContextHolder} to identify the user.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Extract {@code Authorization: Bearer <token>} header.</li>
 *   <li>Validate the JWT signature and expiry via {@link JwtUtil}.</li>
 *   <li>On success: populate {@link SecurityContextHolder} and attach
 *       the user ID as a request attribute ({@code X-User-Id}).</li>
 *   <li>On failure: respond with 401 Unauthorized and stop the chain.</li>
 *   <li>Missing header: respond with 401 immediately.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Attribute key used to pass the authenticated user ID to downstream filters.
     */
    public static final String USER_ID_ATTRIBUTE = "X-User-Id";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            log.error("Missing or malformed Authorization header for {}", request.getRequestURI());
            sendUnauthorized(response, "Missing or malformed Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        Claims claims = jwtUtil.validateAndExtractClaims(token);

        if (claims == null) {
            log.error("Invalid JWT for request {}", request.getRequestURI());
            sendUnauthorized(response, "Invalid or expired JWT token");
            return;
        }

        String userId = claims.getSubject();

        if (StringUtils.hasText(userId)) {
            request.setAttribute(USER_ID_ATTRIBUTE, userId);

            // Only populate the SecurityContext if it is not already set.
            // In a STATELESS setup this will always be null at this point in
            // production; the guard is a safety net for non-standard filter chains.
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                List<SimpleGrantedAuthority> authorities = extractAuthorities(claims);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }


        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("""
                {"status":401,"message":"%s"}
                """.formatted(message));
    }

    private List<SimpleGrantedAuthority> extractAuthorities(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List<?> list) {
            return list.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toString().toUpperCase()))
                    .toList();
        }
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
