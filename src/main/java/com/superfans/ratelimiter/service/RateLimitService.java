package com.superfans.ratelimiter.service;

import com.superfans.ratelimiter.model.RateLimitResult;

/**
 * Contract for rate-limiting decisions.
 *
 * <p>Implementations are expected to be thread-safe — multiple request
 * threads will call {@link #check(String)} concurrently.
 */
public interface RateLimitService {

    /**
     * Checks whether the given user is within their allowed quota and
     * increments the counter atomically.
     *
     * @param userId the authenticated user identifier (JWT subject)
     * @return a {@link RateLimitResult} describing the current quota state
     */
    RateLimitResult check(String userId);

    /**
     * Builds the Redis key for a given user.
     * Kept on the interface so tests and alternative implementations
     * can share the same key format.
     */
    default String buildKey(String userId) {
        return "rate_limit:" + userId;
    }
}
