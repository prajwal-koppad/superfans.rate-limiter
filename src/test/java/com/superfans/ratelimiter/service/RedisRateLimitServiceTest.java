package com.superfans.ratelimiter.service;

import com.superfans.ratelimiter.config.RateLimitProperties;
import com.superfans.ratelimiter.model.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisRateLimitService}.
 *
 * <p>Redis is mocked — no real Redis instance is required.
 * Focuses on verifying business logic: allowed/denied decisions,
 * header values, and failure-open behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisRateLimitService")
class RedisRateLimitServiceTest {

    private static final String USER_ID = "user-42";
    private static final long LIMIT = 100L;
    private static final int WINDOW_MINUTES = 1;

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private DefaultRedisScript<Long> rateLimitScript;
    @Mock
    private RateLimitProperties properties;
    @InjectMocks
    private RedisRateLimitService service;

    @BeforeEach
    void setUp() {
        when(properties.getLimit()).thenReturn(LIMIT);
        when(properties.getWindowMinutes()).thenReturn(WINDOW_MINUTES);
    }

    // -------------------------------------------------------------------------
    // Happy-path: within limit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("First request — should be allowed with remaining = 99")
    void firstRequest_shouldBeAllowed() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(60L);

        RateLimitResult result = service.check(USER_ID);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getCurrentCount()).isEqualTo(1L);
        assertThat(result.getRemaining()).isEqualTo(99L);
        assertThat(result.getLimit()).isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("100th request (exactly at limit) — should still be allowed")
    void hundredthRequest_atLimit_shouldBeAllowed() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(30L);

        RateLimitResult result = service.check(USER_ID);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getRemaining()).isZero();
    }

    // -------------------------------------------------------------------------
    // Limit exceeded
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("101st request — should be denied with HTTP 429")
    void exceedingLimit_shouldBeDenied() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(101L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(45L);

        RateLimitResult result = service.check(USER_ID);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemaining()).isZero();
        assertThat(result.getCurrentCount()).isEqualTo(101L);
        assertThat(result.getRetryAfterSeconds()).isEqualTo(45L);
    }

    @Test
    @DisplayName("Requests far over limit — remaining is clamped to 0, never negative")
    void farOverLimit_remainingClampedToZero() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(500L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(10L);

        RateLimitResult result = service.check(USER_ID);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemaining()).isZero(); // never negative
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Redis unavailable — fail-open allows request")
    void redisUnavailable_shouldFailOpen() {

        when(redisTemplate.execute(
                eq(rateLimitScript),
                anyList(),
                anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        RateLimitResult result = service.check(USER_ID);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getLimit()).isEqualTo(LIMIT);
        assertThat(result.getRemaining()).isEqualTo(LIMIT);
        assertThat(result.getCurrentCount()).isZero();
        assertThat(result.getRetryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("Lua script is called with correct key and window TTL")
    void scriptCalledWithCorrectArguments() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(60L);

        service.check(USER_ID);

        verify(redisTemplate, times(1)).execute(
                eq(rateLimitScript),
                eq(List.of("rate_limit:" + USER_ID)),
                eq("60")
        );
    }

    @Test
    @DisplayName("Different users have isolated counters — different Redis keys")
    void differentUsers_isolatedCounters() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(60L);

        service.check("virat");
        service.check("rohit");

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:virat")), any());
        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:rohit")), any());
    }

    @Test
    @DisplayName("TTL-based retry-after falls back to window seconds when Redis returns -1")
    void ttlNegative_fallsBackToWindowSeconds() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(101L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);

        RateLimitResult result = service.check(USER_ID);

        // Should fall back to window duration (60s)
        assertThat(result.getRetryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("Redis TTL lookup failure — fail-open allows request")
    void redisTtlLookupFailure_shouldFailOpen() {

        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenReturn(50L);

        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        RateLimitResult result = service.check(USER_ID);

        assertThat(result.isAllowed()).isTrue();
    }
}
