package com.superfans.ratelimiter.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Utility component for creating and validating JSON Web Tokens (JWTs).
 *
 * <p>The secret key is injected from configuration and never hard-coded.
 *
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtUtil(
            @Value("${jwt.secret}") String base64Secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        this.expirationMs = expirationMs;
    }

    /**
     * Generates a signed JWT for the given user ID with optional extra claims.
     *
     * @param userId      the sub
     * @param extraClaims any additional payload claims
     * @return signed compact JWT string
     */
    public String generateToken(String userId, Map<String, Object> extraClaims) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .claims(extraClaims)
                .subject(userId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public String generateToken(String userId) {
        return generateToken(userId, Map.of());
    }

    /**
     * Parses and validates a JWT, returning its claims on success.
     *
     * @param token compact JWT string (without the "Bearer " prefix)
     * @return parsed {@link Claims}, or {@code null} if the token is
     * expired, tampered with, or otherwise invalid
     */
    public Claims validateAndExtractClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT validation failed: {}", ex.getMessage());
            return null;
        }
    }
}
