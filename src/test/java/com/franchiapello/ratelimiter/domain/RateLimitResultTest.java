package com.franchiapello.ratelimiter.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitResultTest {

    @Test
    void allowProducesConsistentAllowedResult() {
        RateLimitResult result = RateLimitResult.allow(42);

        assertTrue(result.allowed());
        assertEquals(42, result.remainingTokens());
        assertEquals(0, result.retryAfterMillis());
    }

    @Test
    void denyProducesConsistentDeniedResult() {
        RateLimitResult result = RateLimitResult.deny(500);

        assertFalse(result.allowed());
        assertEquals(0, result.remainingTokens());
        assertEquals(500, result.retryAfterMillis());
    }
}
