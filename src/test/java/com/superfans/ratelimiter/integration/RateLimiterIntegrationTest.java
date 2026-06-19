package com.superfans.ratelimiter.integration;

import com.superfans.ratelimiter.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration test using an embedded Redis server.
 *
 * <p>Tests the full request pipeline:
 * JWT filter → SecurityContext → RateLimitFilter → Redis → Controller
 *
 * <h2>Test isolation via @BeforeEach cleanup</h2>
 * <p>Each test method starts with all {@code rate_limit:*} keys deleted.
 * This makes tests order-independent regardless of whether they share an
 * embedded or real Redis instance. Combined with per-test unique user IDs
 * (via {@link UUID#randomUUID()}), there is zero cross-test state leakage.
 *
 * <h2>Embedded Redis via ApplicationContextInitializer</h2>
 * <p>Using {@link ApplicationContextInitializer} rather than
 * {@code @DynamicPropertySource} ensures the embedded Redis is started and
 * its port is known before Spring resolves the {@code RedisConnectionFactory}
 * bean, regardless of JUnit's class-loading and test-discovery order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = RateLimiterIntegrationTest.EmbeddedRedisInitializer.class)
@TestPropertySource(properties = "rate-limit.limit=100")
@DisplayName("Rate Limiter — Integration Tests")
class RateLimiterIntegrationTest {

    // -------------------------------------------------------------------------
    // Embedded Redis bootstrap
    // -------------------------------------------------------------------------

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * Delete all rate-limit counters before each test so that tests are
     * fully isolated regardless of execution order or Redis instance type.
     */
    @BeforeEach
    void clearRateLimitKeys() {
        Set<String> keys = redisTemplate.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /**
     * Returns a cryptographically unique user ID per call.
     * UUID.randomUUID() is used instead of System.nanoTime() because on
     * Windows the system timer resolution is ~15 ms — two rapid calls in
     * the same test can return the same nanoTime value and accidentally
     * produce the same user ID (and therefore the same Redis key).
     */
    private String uniqueUser() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("First 100 requests all succeed with X-RateLimit-* headers present")
    void first100Requests_allSucceedWithHeaders() throws Exception {
        String authHeader = "Bearer " + jwtUtil.generateToken(uniqueUser());

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/hello").header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-RateLimit-Limit"))
                    .andExpect(header().exists("X-RateLimit-Remaining"))
                    .andExpect(header().exists("X-RateLimit-Reset"));
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("101st request returns HTTP 429 with Retry-After header and JSON body")
    void hundredAndFirstRequest_returns429() throws Exception {
        String authHeader = "Bearer " + jwtUtil.generateToken(uniqueUser());

        // Exhaust the quota (all 100 succeed — @BeforeEach ensures clean state)
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/hello").header("Authorization", authHeader))
                    .andExpect(status().isOk());
        }

        // 101st must be denied
        mockMvc.perform(get("/api/hello").header("Authorization", authHeader))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("Different users have independent rate-limit quotas")
    void differentUsers_haveIndependentQuotas() throws Exception {
        String tokenA = jwtUtil.generateToken(uniqueUser());
        String tokenB = jwtUtil.generateToken(uniqueUser());

        // Use 10 of User A's 100-request quota
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/hello").header("Authorization", "Bearer " + tokenA))
                    .andExpect(status().isOk());
        }
        // User A: count=10, remaining=90

        // User B must have a FULLY independent, FRESH quota.
        // If counters were shared, User B's remaining would be 89 (100 - 10 - 1).
        // A remaining of 99 proves the counters are completely isolated.
        mockMvc.perform(get("/api/hello").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "99"));
    }

    @Test
    @DisplayName("Request without token returns 401 Unauthorized")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("X-RateLimit-Remaining decrements with each successive request")
    void rateLimitRemaining_decrements() throws Exception {
        String authHeader = "Bearer " + jwtUtil.generateToken(uniqueUser());

        // 1st request → remaining = 99
        mockMvc.perform(get("/api/hello").header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "99"));

        // 2nd request → remaining = 98
        mockMvc.perform(get("/api/hello").header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "98"));
    }

    @Test
    @DisplayName("Request to public token endpoint does not require authorization header")
    void publicTokenEndpoint_doesNotRequireAuthHeader() throws Exception {
        mockMvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"userId\": \"prajwal-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").value("prajwal-123"));
    }

    static class EmbeddedRedisInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final RedisServer SERVER;
        private static final int PORT;

        static {
            try {
                PORT = findFreePort();
                SERVER = RedisServer.newRedisServer().port(PORT).build();
                SERVER.start();
            } catch (IOException e) {
                throw new IllegalStateException("Could not start embedded Redis", e);
            }
        }

        private static int findFreePort() throws IOException {
            try (ServerSocket s = new ServerSocket(0)) {
                return s.getLocalPort();
            }
        }

        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    ctx, "spring.data.redis.port=" + PORT);
        }
    }
}
