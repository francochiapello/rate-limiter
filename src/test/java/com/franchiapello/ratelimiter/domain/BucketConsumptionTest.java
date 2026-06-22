package com.franchiapello.ratelimiter.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comportamiento de consumo de {@link Bucket} sin que el tiempo transcurra
 * entre operaciones (todas las llamadas ocurren "en el mismo instante" segun
 * el reloj de prueba). El comportamiento del refill en si esta cubierto por
 * {@link BucketRefillTest}, separado para no mezclar ambas preocupaciones en
 * la misma clase de test.
 */
class BucketConsumptionTest {

    @Test
    void newBucketStartsFullAtCapacity() {
        RateLimiterConfig config = RateLimiterConfig.of(5, 1, Duration.ofSeconds(1));
        Bucket bucket = Bucket.newFull(config, FixedClock.at(0));

        RateLimitResult result = bucket.tryConsume(config);

        assertTrue(result.allowed());
        assertEquals(4, result.remainingTokens());
    }

    @Test
    void allowsConsumptionUpToCapacityThenDenies() {
        RateLimiterConfig config = RateLimiterConfig.of(3, 1, Duration.ofSeconds(1));
        Bucket bucket = Bucket.newFull(config, FixedClock.at(0));

        assertTrue(bucket.tryConsume(config).allowed());
        assertTrue(bucket.tryConsume(config).allowed());
        assertTrue(bucket.tryConsume(config).allowed());

        RateLimitResult fourthAttempt = bucket.tryConsume(config);

        assertFalse(fourthAttempt.allowed());
        assertEquals(0, fourthAttempt.remainingTokens());
    }

    @Test
    void deniedResultReportsPositiveRetryAfter() {
        RateLimiterConfig config = RateLimiterConfig.of(1, 1, Duration.ofSeconds(1));
        Bucket bucket = Bucket.newFull(config, FixedClock.at(0));
        bucket.tryConsume(config); // agota el unico token disponible

        RateLimitResult result = bucket.tryConsume(config);

        assertFalse(result.allowed());
        assertTrue(result.retryAfterMillis() > 0,
                "retryAfterMillis deberia ser positivo cuando se rechaza la request");
    }

    @Test
    void allowedResultNeverReportsRetryAfter() {
        RateLimiterConfig config = RateLimiterConfig.of(5, 1, Duration.ofSeconds(1));
        Bucket bucket = Bucket.newFull(config, FixedClock.at(0));

        RateLimitResult result = bucket.tryConsume(config);

        assertEquals(0, result.retryAfterMillis());
    }

    @Test
    void capacityOfOneAllowsExactlyOneRequestThenDenies() {
        // Caso limite: el bucket mas restrictivo posible.
        RateLimiterConfig config = RateLimiterConfig.of(1, 1, Duration.ofSeconds(1));
        Bucket bucket = Bucket.newFull(config, FixedClock.at(0));

        assertTrue(bucket.tryConsume(config).allowed());
        assertFalse(bucket.tryConsume(config).allowed());
    }
}
