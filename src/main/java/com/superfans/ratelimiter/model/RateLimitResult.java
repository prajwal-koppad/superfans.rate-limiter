package com.superfans.ratelimiter.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Encapsulates the result of a single rate-limit check.
 * <p>
 * This rich result object is used to populate the standard
 * {@code X-RateLimit-*} response headers, giving API consumers
 * visibility into their quota consumption.
 */
@Getter
@Builder
public class RateLimitResult {

    /**
     * Whether this request is within the allowed limit.
     */
    private final boolean allowed;

    /**
     * The configured maximum requests per window.
     */
    private final long limit;

    /**
     * The current request count after this request.
     */
    private final long currentCount;

    /**
     * How many requests remain before hitting the limit (clamped to 0).
     */
    private final long remaining;

    /**
     * Seconds until the counter resets (i.e. the Redis TTL).
     * Used to populate the {@code Retry-After} header on 429 responses.
     */
    private final long retryAfterSeconds;
}
