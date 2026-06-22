package com.franchiapello.ratelimiter.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comportamiento de refill de {@link Bucket} a lo largo del tiempo, usando
 * {@link FixedClock} para controlar exactamente cuanto tiempo "transcurre"
 * entre operaciones, sin Thread.sleep.
 */
class BucketRefillTest {

    @Test
    void refillsOneTokenAfterOnePeriodElapses() {
        RateLimiterConfig config = RateLimiterConfig.of(1, 1, Duration.ofSeconds(1));
        FixedClock clock = FixedClock.at(0);
        Bucket bucket = Bucket.newFull(config, clock);
        bucket.tryConsume(config); // agota el unico token

        clock.advanceMillis(1000); // exactamente un periodo de refill
        RateLimitResult result = bucket.tryConsume(config);

        assertTrue(result.allowed(), "deberia haber un token disponible tras un periodo completo");
    }

    @Test
    void doesNotRefillBeforeFullPeriodElapses() {
        RateLimiterConfig config = RateLimiterConfig.of(1, 1, Duration.ofSeconds(1));
        FixedClock clock = FixedClock.at(0);
        Bucket bucket = Bucket.newFull(config, clock);
        bucket.tryConsume(config); // agota el unico token

        clock.advanceMillis(999); // un milisegundo antes de completar el periodo
        RateLimitResult result = bucket.tryConsume(config);

        assertFalse(result.allowed(), "no deberia refillar antes de completar el periodo");
    }

    @Test
    void refillNeverExceedsCapacity() {
        RateLimiterConfig config = RateLimiterConfig.of(3, 1, Duration.ofSeconds(1));
        FixedClock clock = FixedClock.at(0);
        Bucket bucket = Bucket.newFull(config, clock);
        // bucket arranca lleno (3); no se consume nada

        clock.advanceMillis(10_000); // mucho tiempo: refillaria de mas si no hubiera tope
        RateLimitResult result = bucket.tryConsume(config);

        // tras consumir uno, deberian quedar como maximo capacity-1, nunca mas
        assertEquals(2, result.remainingTokens());
    }

    @Test
    void multipleElapsedPeriodsRefillProportionally() {
        RateLimiterConfig config = RateLimiterConfig.of(10, 2, Duration.ofSeconds(1));
        FixedClock clock = FixedClock.at(0);
        Bucket bucket = Bucket.newFull(config, clock);
        // consumir todo: 10 tokens
        for (int i = 0; i < 10; i++) {
            bucket.tryConsume(config);
        }

        clock.advanceMillis(3000); // 3 periodos completos -> 3 * 2 = 6 tokens

        RateLimitResult result = bucket.tryConsume(config);

        assertTrue(result.allowed());
        // se repusieron 6, se consume 1 en este tryConsume -> quedan 5
        assertEquals(5, result.remainingTokens());
    }

    @Test
    void refillDoesNotDriftAcrossManySmallIntervals() {
        // Caso critico: si el timestamp se reseteara a "ahora" en cada refill
        // en vez de avanzar en multiplos exactos del periodo, las fracciones
        // de periodo se perderian en cada llamada y, acumuladas, el bucket
        // refillaria menos tokens de los que realmente corresponden.
        //
        // Avanzamos el reloj en pasos de 300ms, que NO coinciden con bordes
        // de periodo (1000ms). Cada avance de 300ms dispara un tryConsume, lo
        // cual fuerza un calculo de refill en un punto intermedio del periodo.
        // Con avance correcto (sin drift), 10 pasos de 300ms acumulan 3000ms
        // reales = exactamente 3 periodos completos = 3 tokens refillados.
        // Con drift (reset a "ahora" en cada refill), cada paso de 300ms ve
        // "elapsed < periodMillis" y nunca refilla nada, perdiendo los 3000ms
        // acumulados por completo.
        RateLimiterConfig config = RateLimiterConfig.of(100, 1, Duration.ofSeconds(1));
        FixedClock clock = FixedClock.at(0);
        Bucket bucket = Bucket.newFull(config, clock);
        for (int i = 0; i < 100; i++) {
            bucket.tryConsume(config); // agota el bucket completo (queda en 0)
        }

        RateLimitResult lastResult = null;
        for (int i = 0; i < 10; i++) {
            clock.advanceMillis(300);
            lastResult = bucket.tryConsume(config);
        }

        // 3000ms transcurridos en total / 1000ms por periodo = 3 tokens
        // refillados a lo largo de las 10 llamadas. De esos 3, el propio loop
        // de tryConsume fue consumiendo a medida que aparecian: el ultimo
        // tryConsume (el de t=3000ms, que completa el 3er periodo) deberia
        // encontrar el bucket en 0 y refillar exactamente 1 token disponible
        // para esa llamada, permitiendola.
        assertTrue(lastResult.allowed(),
                "tras 3000ms con periodos de 1000ms deberian haberse refillado "
                        + "3 tokens en total; sin drift, el ultimo intento deberia ser permitido");
    }

    @Test
    void capacityOfOneNeverRefillsAboveOne() {
        RateLimiterConfig config = RateLimiterConfig.of(1, 5, Duration.ofSeconds(1));
        FixedClock clock = FixedClock.at(0);
        Bucket bucket = Bucket.newFull(config, clock);
        bucket.tryConsume(config); // agota el unico token

        clock.advanceMillis(1000); // un periodo completo; refillTokens=5 pero capacity=1

        RateLimitResult result = bucket.tryConsume(config);

        assertTrue(result.allowed());
        assertEquals(0, result.remainingTokens(), "no deberia exceder capacity aunque refillTokens sea mayor");
    }
}
