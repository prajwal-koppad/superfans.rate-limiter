package com.superfans.ratelimiter.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized rate-limit configuration bound from application.yml.
 * <p>
 * Allows operators to tune limits without recompiling:
 * <pre>
 *   rate-limit:
 *     limit: 100
 *     window-minutes: 1
 * </pre>
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    @Min(1)
    @Max(100_000)
    private long limit = 100;

    @Min(1)
    @Max(60)
    private int windowMinutes = 1;
}
