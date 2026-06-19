package com.superfans.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.superfans.ratelimiter.model.RateLimitResult;
import com.superfans.ratelimiter.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>Uses Spring's {@link MockHttpServletRequest}/{@link MockHttpServletResponse}
 * to avoid spinning up a full servlet container.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private static final String USER_ID = "user-99";
    @Mock
    private RateLimitService rateLimitService;
    @InjectMocks
    private RateLimitFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        // Inject ObjectMapper manually because @InjectMocks doesn't wire Spring beans
        filter = new RateLimitFilter(rateLimitService, new ObjectMapper()
                .registerModule(new JavaTimeModule()));

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();

        // Simulate authenticated user in SecurityContext
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        request.setAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID);
    }

    // -------------------------------------------------------------------------
    // Allowed requests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Allowed request — chain continues and X-RateLimit headers are present")
    void allowedRequest_chainContinues_headersSet() throws Exception {
        when(rateLimitService.check(eq(USER_ID)))
                .thenReturn(allowedResult(50L, 50L, 60L));

        filter.doFilter(request, response, chain);

        // Chain should have continued (downstream filter/servlet was called)
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);

        // Standard rate-limit headers must be present
        assertThat(response.getHeader(RateLimitFilter.HEADER_LIMIT)).isEqualTo("100");
        assertThat(response.getHeader(RateLimitFilter.HEADER_REMAINING)).isEqualTo("50");
        assertThat(response.getHeader(RateLimitFilter.HEADER_RESET)).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Denied requests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Denied request — returns 429 and does NOT continue the chain")
    void deniedRequest_returns429_chainStopped() throws Exception {
        when(rateLimitService.check(eq(USER_ID)))
                .thenReturn(deniedResult(101L, 45L));

        filter.doFilter(request, response, chain);

        // Chain must have been stopped
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/json");
    }

    @Test
    @DisplayName("Denied request — Retry-After header matches retryAfterSeconds")
    void deniedRequest_retryAfterHeaderCorrect() throws Exception {
        when(rateLimitService.check(eq(USER_ID)))
                .thenReturn(deniedResult(101L, 42L));

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RateLimitFilter.HEADER_RETRY_AFTER)).isEqualTo("42");
    }

    @Test
    @DisplayName("Denied request — response body contains 429 status and message")
    void deniedRequest_responseBodyIsCorrect() throws Exception {
        when(rateLimitService.check(eq(USER_ID)))
                .thenReturn(deniedResult(101L, 30L));

        filter.doFilter(request, response, chain);

        String body = response.getContentAsString();
        assertThat(body).contains("429");
        assertThat(body).contains("Rate limit exceeded");
    }

    // -------------------------------------------------------------------------
    // Missing principal — filter should not crash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("No authenticated user — filter delegates to chain (Security handles it)")
    void noAuthenticatedUser_delegatesToChain() throws Exception {
        SecurityContextHolder.clearContext();
        request.removeAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE);

        filter.doFilter(request, response, chain);

        // Rate-limit service should never be called if there's no user
        verify(rateLimitService, never()).check(any());
        assertThat(chain.getRequest()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    private RateLimitResult allowedResult(long count, long remaining, long retryAfter) {
        return RateLimitResult.builder()
                .allowed(true).limit(100).currentCount(count)
                .remaining(remaining).retryAfterSeconds(retryAfter).build();
    }

    private RateLimitResult deniedResult(long count, long retryAfter) {
        return RateLimitResult.builder()
                .allowed(false).limit(100).currentCount(count)
                .remaining(0).retryAfterSeconds(retryAfter).build();
    }
}
