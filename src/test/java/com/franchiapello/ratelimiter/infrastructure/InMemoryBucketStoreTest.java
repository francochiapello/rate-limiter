package com.franchiapello.ratelimiter.infrastructure;

import com.franchiapello.ratelimiter.domain.Bucket;
import com.franchiapello.ratelimiter.domain.RateLimiterConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryBucketStoreTest {

    private final RateLimiterConfig config = RateLimiterConfig.of(10, 1, Duration.ofSeconds(1));

    @Test
    void secondCallForSameClientReturnsTheSameBucketInstance() {
        InMemoryBucketStore store = new InMemoryBucketStore();

        Bucket first = store.getOrCreate("client-1", config);
        Bucket second = store.getOrCreate("client-1", config);

        assertSame(first, second, "el mismo clientId debe devolver siempre la misma instancia de Bucket");
    }

    @Test
    void differentClientsGetIndependentBuckets() {
        InMemoryBucketStore store = new InMemoryBucketStore();

        Bucket bucketA = store.getOrCreate("client-a", config);
        Bucket bucketB = store.getOrCreate("client-b", config);

        // Si fueran el mismo bucket, consumir todo en uno afectaria al otro.
        // Se valida indirectamente: deben ser instancias distintas.
        assertTrue(bucketA != bucketB, "clientes distintos deben tener buckets independientes");
    }

    @Test
    void concurrentFirstAccessForSameClientCreatesExactlyOneBucket() throws InterruptedException {
        // El riesgo que este test cubre: si getOrCreate no fuera atomico,
        // multiples threads podrian evaluar "no existe" al mismo tiempo en la
        // PRIMERA llamada para un clientId y crear varios buckets distintos,
        // de los cuales solo uno terminaria "ganando" en el mapa — pero cada
        // thread se quedaria con la referencia que el mismo creo, no
        // necesariamente la que quedo almacenada, llevando a estado inconsistente.
        InMemoryBucketStore store = new InMemoryBucketStore();
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<Bucket> seenBuckets = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    seenBuckets.add(store.getOrCreate("shared-client", config));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(finished, "el test no deberia tardar mas de 10 segundos");
        assertEquals(threadCount, seenBuckets.size());
        long distinctInstances = seenBuckets.stream().distinct().count();
        assertEquals(1, distinctInstances,
                "todos los threads deberian recibir la MISMA instancia de Bucket para el mismo clientId");
    }
}
