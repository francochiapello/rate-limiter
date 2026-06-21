package com.franchiapello.ratelimiter.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica la propiedad de concurrencia mas importante del dominio: bajo
 * contencion real de multiples threads sobre el MISMO bucket, la cantidad
 * total de requests permitidas nunca excede la capacidad configurada. Esto
 * es lo que demuestra que el lock interno de {@link Bucket} efectivamente
 * previene la condicion de carrera (dos threads leyendo "hay tokens" antes
 * de que ninguno haya decrementado, y ambos consumiendo el mismo token).
 */
class BucketConcurrencyTest {

    @Test
    void concurrentRequestsNeverExceedCapacity() throws InterruptedException {
        long capacity = 50;
        RateLimiterConfig config = RateLimiterConfig.of(capacity, 1, Duration.ofMinutes(1));
        // Periodo de refill largo (1 minuto) a proposito: durante la ventana
        // del test (milisegundos reales de ejecucion) no debe ocurrir ningun
        // refill que contamine el resultado. Este test usa Clock.systemUTC()
        // (via Bucket.newFull(config)) porque lo que se quiere probar es el
        // lock bajo concurrencia REAL de threads, no el calculo de refill.
        Bucket bucket = Bucket.newFull(config);

        int threadCount = 200; // mucho mayor que la capacidad, a proposito
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // todos los threads arrancan a la vez
                    RateLimitResult result = bucket.tryConsume(config);
                    if (result.allowed()) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        readyLatch.await(); // esperar a que todos los threads esten listos
        startLatch.countDown(); // liberar a todos simultaneamente
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(finished, "el test no deberia tardar mas de 10 segundos");
        assertEquals(capacity, allowedCount.get(),
                "exactamente 'capacity' requests deberian ser permitidas, ni una mas ni una menos, "
                        + "sin importar cuantos threads compitan simultaneamente");
    }
}
