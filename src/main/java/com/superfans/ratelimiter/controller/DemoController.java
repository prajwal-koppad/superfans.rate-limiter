package com.superfans.ratelimiter.controller;

import com.superfans.ratelimiter.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Demo controller exposing test endpoints.
 *
 * <p>
 * In a real gateway these routes would be proxied to downstream services.
 * They are included here to demonstrate the rate-limiting behavior end-to-end.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DemoController {

    private final JwtUtil jwtUtil;

    /**
     * Basic authenticated endpoint.
     * Returns the authenticated user ID and a success message.
     */
    @GetMapping("/hello")
    public ResponseEntity<Map<String, String>> hello(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "Hello, " + auth.getName() + "!",
                "status", "success"));
    }

    /**
     * Simulated data endpoint — useful for rapid fire rate-limit testing.
     */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getData(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "user", auth.getName(),
                "data", "some-protected-resource",
                "timestamp", System.currentTimeMillis()));
    }

    /**
     * Token issuance endpoint (no auth required).
     *
     * <p>
     * In production this would validate credentials; here it generates a token
     * for any submitted user ID so the assignment can be tested without a
     * full identity provider.
     *
     * @param body JSON body with {@code userId} field
     */
    @PostMapping("/auth/token")
    public ResponseEntity<Map<String, String>> issueToken(
            @RequestBody Map<String, String> body) {
        String userId = body.getOrDefault("userId", "anonymous");
        String token = jwtUtil.generateToken(userId);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", userId,
                "type", "Bearer"));
    }

}