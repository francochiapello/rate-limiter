package com.franchiapello.ratelimiter.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * La validacion en el constructor de {@link RateLimiterConfig} es codigo de
 * dominio real (no boilerplate), por eso se testea: una config invalida que
 * se cuela silenciosamente produce un Bucket con comportamiento indefinido
 * (ver {@link BucketRefillTest} para que tan sensible es el calculo de refill
 * a estos valores).
 */
class RateLimiterConfigTest {

    @Test
    void createsValidConfig() {
        RateLimiterConfig config = RateLimiterConfig.of(100, 10, Duration.ofSeconds(1));

        assertEquals(100, config.capacity());
        assertEquals(10, config.refillTokens());
        assertEquals(Duration.ofSeconds(1), config.refillPeriod());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void rejectsNonPositiveCapacity(long invalidCapacity) {
        assertThrows(IllegalArgumentException.class,
                () -> RateLimiterConfig.of(invalidCapacity, 10, Duration.ofSeconds(1)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void rejectsNonPositiveRefillTokens(long invalidRefillTokens) {
        assertThrows(IllegalArgumentException.class,
                () -> RateLimiterConfig.of(100, invalidRefillTokens, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsNullRefillPeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> RateLimiterConfig.of(100, 10, null));
    }

    @Test
    void rejectsZeroRefillPeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> RateLimiterConfig.of(100, 10, Duration.ZERO));
    }

    @Test
    void rejectsNegativeRefillPeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> RateLimiterConfig.of(100, 10, Duration.ofSeconds(-1)));
    }

    @Test
    void allowsRefillTokensGreaterThanCapacity() {
        // Caso limite deliberado: refillTokens > capacity es valido (el Min en
        // Bucket.refill se encarga de no exceder la capacidad), no es un error
        // de configuracion. Significa "se repone todo de una sola vez".
        RateLimiterConfig config = RateLimiterConfig.of(10, 100, Duration.ofSeconds(1));

        assertEquals(10, config.capacity());
        assertEquals(100, config.refillTokens());
    }
}
