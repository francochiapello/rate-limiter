package com.franchiapello.ratelimiter.infrastructure;

import com.franchiapello.ratelimiter.domain.Bucket;
import com.franchiapello.ratelimiter.domain.BucketStore;
import com.franchiapello.ratelimiter.domain.RateLimiterConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Unica implementacion de {@link BucketStore} en este proyecto: mantiene los
 * buckets en memoria, en un unico proceso. No hay coordinacion entre instancias
 * si la aplicacion corre en mas de un nodo (ver DESIGN.md, seccion 7.1 para la
 * evolucion a un backend distribuido).
 *
 * <p>La atomicidad de "buscar o crear" se apoya en
 * {@link ConcurrentHashMap#computeIfAbsent}, que garantiza que la funcion de
 * creacion se ejecute como maximo una vez por clave incluso bajo concurrencia.
 * No se agrega ningun lock propio aca: la estructura ya resuelve el problema.
 */
public class InMemoryBucketStore implements BucketStore {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Bucket getOrCreate(String clientId, RateLimiterConfig config) {
        return buckets.computeIfAbsent(clientId, id -> Bucket.newFull(config));
    }
}
