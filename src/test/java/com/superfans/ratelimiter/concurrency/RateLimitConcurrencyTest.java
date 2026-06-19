package com.superfans.ratelimiter.concurrency;

import com.superfans.ratelimiter.config.RateLimitProperties;
import com.superfans.ratelimiter.model.RateLimitResult;
import com.superfans.ratelimiter.service.RedisRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Concurrency tests for {@link RedisRateLimitService}.
 *
 * <p>Simulates high-concurrency traffic using a thread pool.
 * Because we are unit-testing (Redis is mocked), these tests validate
 * the service's response to concurrent calls and that the allowed/denied
 * counts sum correctly — the real atomicity guarantee is provided by Redis Lua.
 *
 * <p>A separate integration test ({@code RateLimiterIntegrationTest}) validates
 * the full stack including real Redis atomicity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RateLimitService — Concurrency")
class RateLimitConcurrencyTest {

    private static final int THREAD_COUNT = 200;
    private static final long LIMIT = 100L;
    /**
     * Simulates Redis by providing an incrementing counter.
     */
    private final AtomicLong redisCounter = new AtomicLong(0);
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private DefaultRedisScript<Long> rateLimitScript;
    @Mock
    private RateLimitProperties properties;
    private RedisRateLimitService service;

    @BeforeEach
    void setUp() {
        when(properties.getLimit()).thenReturn(LIMIT);
        when(properties.getWindowMinutes()).thenReturn(1);

        // Simulate atomic Redis INCR
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), anyString()))
                .thenAnswer(inv -> redisCounter.incrementAndGet());
        when(redisTemplate.getExpire(anyString(), any())).thenReturn(60L);

        service = new RedisRateLimitService(redisTemplate, rateLimitScript, properties);
    }

    @Test
    @DisplayName("200 concurrent requests — exactly 100 allowed and 100 denied")
    void concurrentRequests_exactBoundaryRespected() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        AtomicLong allowedCount = new AtomicLong(0);
        AtomicLong deniedCount = new AtomicLong(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start simultaneously
                    RateLimitResult result = service.check("concurrent-user");
                    if (result.isAllowed()) allowedCount.incrementAndGet();
                    else deniedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // fire all threads
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).as("All threads should finish within 10 seconds").isTrue();
        assertThat(allowedCount.get()).isEqualTo(LIMIT);
        assertThat(deniedCount.get()).isEqualTo(THREAD_COUNT - LIMIT);
        assertThat(allowedCount.get() + deniedCount.get()).isEqualTo(THREAD_COUNT);

        executor.shutdown();
    }

    @Test
    @DisplayName("Multiple users — each has their own independent limit")
    void multipleUsers_independentLimits() throws InterruptedException {
        // Each user gets their own Redis counter
        AtomicLong alice = new AtomicLong(0);
        AtomicLong bob = new AtomicLong(0);

        when(redisTemplate.execute(eq(rateLimitScript), eq(java.util.List.of("rate_limit:virat")), anyString()))
                .thenAnswer(inv -> alice.incrementAndGet());
        when(redisTemplate.execute(eq(rateLimitScript), eq(java.util.List.of("rate_limit:rohit")), anyString()))
                .thenAnswer(inv -> bob.incrementAndGet());

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch doneLatch = new CountDownLatch(200);

        AtomicLong aliceAllowed = new AtomicLong(0);
        AtomicLong bobAllowed = new AtomicLong(0);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                if (service.check("virat").isAllowed()) aliceAllowed.incrementAndGet();
                doneLatch.countDown();
            });
            executor.submit(() -> {
                if (service.check("rohit").isAllowed()) bobAllowed.incrementAndGet();
                doneLatch.countDown();
            });
        }

        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(aliceAllowed.get()).isEqualTo(LIMIT);
        assertThat(bobAllowed.get()).isEqualTo(LIMIT);
    }
}
