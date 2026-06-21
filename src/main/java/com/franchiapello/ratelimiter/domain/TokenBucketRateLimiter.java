package com.franchiapello.ratelimiter.domain;

import java.util.Objects;

/**
 * Implementacion de {@link RateLimiter} basada en Token Bucket (ver
 * DESIGN.md, seccion 2, para la comparacion con otros algoritmos).
 *
 * <p>Esta clase es pura orquestacion: no tiene estado propio mas alla de sus
 * dos colaboradores. Resuelve la politica del cliente via
 * {@link RateLimiterConfigProvider}, obtiene (o crea) su {@link Bucket} via
 * {@link BucketStore}, y delega en el bucket la decision de permitir o
 * rechazar. Toda la logica de concurrencia y de calculo de tokens vive en
 * {@link Bucket}, no aca — esta clase no necesita sincronizacion propia
 * porque no tiene estado mutable compartido entre invocaciones.
 *
 * <p>Ambos colaboradores se reciben por constructor (inyeccion de
 * dependencias manual, sin Spring): permite testear esta clase con un
 * {@link BucketStore} y un {@link RateLimiterConfigProvider} de prueba,
 * sin levantar infraestructura real.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final BucketStore bucketStore;
    private final RateLimiterConfigProvider configProvider;

    public TokenBucketRateLimiter(BucketStore bucketStore, RateLimiterConfigProvider configProvider) {
        this.bucketStore = Objects.requireNonNull(bucketStore, "bucketStore must not be null");
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider must not be null");
    }

    @Override
    public RateLimitResult tryAcquire(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be null or blank");
        }
        RateLimiterConfig config = configProvider.resolve(clientId);
        Bucket bucket = bucketStore.getOrCreate(clientId, config);
        return bucket.tryConsume(config);
    }
}
