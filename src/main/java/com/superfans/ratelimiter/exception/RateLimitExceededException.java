package com.superfans.ratelimiter.exception;

import lombok.Getter;

/**
 * Thrown when a user exceeds their configured request quota.
 * Carries the retry-after duration so callers can populate
 * the {@code Retry-After} HTTP response header.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final String userId;

    public RateLimitExceededException(String userId, long retryAfterSeconds) {
        super(String.format(
                "Rate limit exceeded for user '%s'. Retry after %d seconds.",
                userId, retryAfterSeconds));
        this.userId = userId;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
