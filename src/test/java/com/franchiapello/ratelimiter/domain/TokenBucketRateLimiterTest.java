package com.franchiapello.ratelimiter.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de {@link TokenBucketRateLimiter} usando mocks de sus dos
 * colaboradores ({@link BucketStore} y {@link RateLimiterConfigProvider},
 * ambos interfaces). {@link Bucket} se usa como instancia REAL (construida
 * con {@link FixedClock}), no mockeada: es una clase final que representa
 * estado real, no un puerto del dominio, asi que no tiene sentido convertirla
 * en interface solo para poder mockearla — eso seria abstraccion innecesaria.
 * Usar un Bucket real ademas verifica la orquestacion de punta a punta (salvo
 * el store y el provider), en vez de solo confirmar que se llamo a un metodo.
 *
 * <p>El objetivo de esta clase de test NO es reprobar el algoritmo de Token
 * Bucket (eso ya esta cubierto exhaustivamente por
 * {@link BucketConsumptionTest}, {@link BucketRefillTest} y
 * {@link BucketConcurrencyTest}) — es probar que {@link TokenBucketRateLimiter}
 * resuelve la config correcta, obtiene el bucket correcto, y propaga su
 * resultado sin alterarlo.
 */
@ExtendWith(MockitoExtension.class)
class TokenBucketRateLimiterTest {

    @Mock
    private BucketStore bucketStore;

    @Mock
    private RateLimiterConfigProvider configProvider;

    @Test
    void resolvesConfigBeforeFetchingBucketAndDelegatesConsumption() {
        RateLimiterConfig config = RateLimiterConfig.of(100, 10, Duration.ofSeconds(1));
        Bucket realBucket = Bucket.newFull(config, FixedClock.at(0));

        when(configProvider.resolve("client-1")).thenReturn(config);
        when(bucketStore.getOrCreate("client-1", config)).thenReturn(realBucket);

        TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(bucketStore, configProvider);
        RateLimitResult result = rateLimiter.tryAcquire("client-1");

        assertTrue(result.allowed());
        assertEquals(99, result.remainingTokens());
        verify(configProvider).resolve("client-1");
        verify(bucketStore).getOrCreate("client-1", config);
    }

    @Test
    void propagatesDenialFromBucketWithoutAlteringIt() {
        RateLimiterConfig config = RateLimiterConfig.of(1, 1, Duration.ofSeconds(1));
        Bucket realBucket = Bucket.newFull(config, FixedClock.at(0));
        realBucket.tryConsume(config); // agota el unico token disponible

        when(configProvider.resolve("client-2")).thenReturn(config);
        when(bucketStore.getOrCreate("client-2", config)).thenReturn(realBucket);

        TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(bucketStore, configProvider);
        RateLimitResult result = rateLimiter.tryAcquire("client-2");

        assertFalse(result.allowed());
        assertEquals(0, result.remainingTokens());
    }

    @Test
    void rejectsNullClientId() {
        TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(bucketStore, configProvider);

        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire(null));
    }

    @Test
    void rejectsBlankClientId() {
        TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(bucketStore, configProvider);

        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("   "));
    }

    @Test
    void rejectsNullBucketStoreInConstructor() {
        assertThrows(NullPointerException.class,
                () -> new TokenBucketRateLimiter(null, configProvider));
    }

    @Test
    void rejectsNullConfigProviderInConstructor() {
        assertThrows(NullPointerException.class,
                () -> new TokenBucketRateLimiter(bucketStore, null));
    }
}
