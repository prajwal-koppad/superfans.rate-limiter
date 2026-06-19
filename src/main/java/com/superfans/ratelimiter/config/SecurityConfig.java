package com.superfans.ratelimiter.config;

import com.superfans.ratelimiter.filter.JwtAuthenticationFilter;
import com.superfans.ratelimiter.filter.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security filter chain configuration.
 *
 * <h2>Filter Order</h2>
 * <ol>
 *   <li>{@link JwtAuthenticationFilter} — validates the JWT and populates the SecurityContext.</li>
 *   <li>{@link RateLimitFilter} — enforces per-user quotas using the authenticated principal.</li>
 * </ol>
 *
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Filter chain for public endpoints.
     * We define a separate chain with a higher priority (@Order(1)) so that requests to these
     * specific endpoints never even pass through our custom JWT or RateLimit filters.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/health", "/api/auth/token")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Filter chain for secured endpoints.
     * All requests not matched by the public chain fall through to this chain.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securedFilterChain(HttpSecurity http,
                                                  JwtAuthenticationFilter jwtFilter,
                                                  RateLimitFilter rateLimitFilter) throws Exception {

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // JWT filter runs before Spring's default username/password filter
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // Rate-limit filter runs after JWT so the principal is available
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
