package com.superfans.ratelimiter.filter;

import com.superfans.ratelimiter.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JwtAuthenticationFilter}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    private static final String VALID_TOKEN = "eyJhbGciOiJIUzI1NiJ9.valid";
    private static final String USER_ID = "user-jwt-test";
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private Claims claims;
    @InjectMocks
    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // Valid token
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Valid Bearer token — SecurityContext populated, chain continues")
    void validToken_populatesSecurityContext_continuesChain() throws Exception {
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtUtil.validateAndExtractClaims(VALID_TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(USER_ID);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("Valid token — userId is set as request attribute for RateLimitFilter")
    void validToken_userIdAttributeSet() throws Exception {
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtUtil.validateAndExtractClaims(VALID_TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(USER_ID);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE))
                .isEqualTo(USER_ID);
    }

    // -------------------------------------------------------------------------
    // Missing / malformed header
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Missing Authorization header — returns 401, chain stops")
    void missingHeader_returns401() throws Exception {
        // No Authorization header added

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull(); // chain was stopped
        verify(jwtUtil, never()).validateAndExtractClaims(anyString());
    }

    @Test
    @DisplayName("Authorization header without Bearer prefix — returns 401")
    void noBearerPrefix_returns401() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    // -------------------------------------------------------------------------
    // Invalid / expired token
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Expired or tampered token — returns 401, chain stops")
    void invalidToken_returns401() throws Exception {
        request.addHeader("Authorization", "Bearer tampered.token.here");
        when(jwtUtil.validateAndExtractClaims(anyString())).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("401 response body contains JSON with status field")
    void invalidToken_responseBodyIsJson() throws Exception {
        request.addHeader("Authorization", "Bearer bad.token");
        when(jwtUtil.validateAndExtractClaims(anyString())).thenReturn(null);

        filter.doFilter(request, response, chain);

        String body = response.getContentAsString();
        assertThat(body).contains("401");
        assertThat(response.getContentType()).contains("application/json");
    }
}
