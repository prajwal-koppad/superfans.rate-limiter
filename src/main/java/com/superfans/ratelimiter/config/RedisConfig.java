package com.superfans.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis infrastructure configuration.
 * <p>
 * Key decisions:
 * <ul>
 *   <li>Uses {@link StringRedisSerializer} for keys and values so that keys are
 *       human-readable in the Redis CLI ({@code redis-cli keys "rate_limit:*"}).</li>
 *   <li>Pre-compiles the Lua rate-limit script as a Spring-managed bean so it
 *       is only parsed once and re-used on every request.</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * Lua script that atomically increments a counter and sets the TTL
     * on the first increment. Both operations happen inside a single
     * Redis command, eliminating the TOCTOU race condition that would
     * exist if INCR and EXPIRE were issued as separate commands.
     *
     * <p>KEYS[1] = the rate-limit key (e.g. "rate_limit:user123")
     * <p>ARGV[1] = window TTL in seconds
     * <p>Returns the new counter value as a Long.
     */
    static final String RATE_LIMIT_LUA_SCRIPT = """
            local key    = KEYS[1]
            local window = tonumber(ARGV[1])
            local count  = redis.call('INCR', key)
            if count == 1 then
                redis.call('EXPIRE', key, window)
            end
            return count
            """;

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Pre-compiled Lua script bean. Spring caches the SHA digest and uses
     * EVALSHA for subsequent calls, reducing latency and network overhead.
     */
    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(RATE_LIMIT_LUA_SCRIPT);
        script.setResultType(Long.class);
        return script;
    }
}
