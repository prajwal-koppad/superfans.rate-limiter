package com.superfans.ratelimiter.service;

import com.superfans.ratelimiter.config.RateLimitProperties;
import com.superfans.ratelimiter.model.RateLimitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed implementation of {@link RateLimitService}.
 *
 * <h2>Thread Safety</h2>
 * <p>All counter increments are performed inside a single Redis Lua script.
 * Lua scripts execute atomically in Redis — no other command can interleave
 * between the INCR and EXPIRE calls, eliminating the TOCTOU race condition
 * that would exist with two separate commands.
 *
 * <h2>Algorithm — Fixed Window Counter</h2>
 * <ol>
 *   <li>A Redis key {@code rate_limit:<userId>} is atomically incremented.</li>
 *   <li>If this is the first increment (count == 1), a TTL equal to the
 *       configured window is set on the key.</li>
 *   <li>Once the key expires, the window resets automatically.</li>
 * </ol>
 *
 * <h2>Performance</h2>
 * <p>Spring caches the Lua script's SHA-1 digest and uses
 * {@code EVALSHA} for subsequent executions, saving the overhead of
 * sending the full script body on every request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRateLimitService implements RateLimitService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;
    private final RateLimitProperties properties;

    @Override
    public RateLimitResult check(String userId) {

        String key = buildKey(userId);
        long windowSeconds = (long) properties.getWindowMinutes() * 60;
        long limit = properties.getLimit();

        try {

            long count = redisTemplate.execute(
                    rateLimitScript,
                    List.of(key),
                    String.valueOf(windowSeconds));

            long remaining = Math.max(0, limit - count);
            boolean allowed = count <= limit;

            long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            long retryAfterSeconds =
                    ttl > 0
                            ? ttl
                            : windowSeconds;

            return RateLimitResult.builder()
                    .allowed(allowed)
                    .limit(limit)
                    .currentCount(count)
                    .remaining(remaining)
                    .retryAfterSeconds(retryAfterSeconds)
                    .build();

        } catch (RedisConnectionFailureException ex) {

            log.error("Redis unavailable while rate limiting user '{}'. Failing open.", userId, ex);

            return RateLimitResult.builder()
                    .allowed(true)
                    .limit(limit)
                    .currentCount(0)
                    .remaining(limit)
                    .retryAfterSeconds(0)
                    .build();
        }
    }
}
