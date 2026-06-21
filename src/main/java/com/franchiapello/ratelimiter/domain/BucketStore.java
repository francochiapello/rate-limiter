package com.franchiapello.ratelimiter.domain;

/**
 * Puerto de almacenamiento de buckets por cliente. El dominio depende de esta
 * interface, no de una estructura de datos concreta, para poder testear
 * {@code TokenBucketRateLimiter} de forma aislada (inyectando un store falso)
 * y para dejar un punto de extension claro si el almacenamiento necesita
 * evolucionar a un backend distribuido (ver DESIGN.md, seccion 4 y 7.1).
 *
 * <p>Expone un unico metodo, {@link #getOrCreate}, en vez de {@code get} +
 * {@code save} separados. Si esas dos operaciones estuvieran separadas, la
 * responsabilidad de orquestar "si no existe, crear; si existe, traer"
 * recaeria en quien llama, lo cual abre una carrera bajo concurrencia: dos
 * threads podrian evaluar "no existe" al mismo tiempo y crear dos buckets
 * distintos para el mismo cliente. Al exponer una sola operacion atomica,
 * esa garantia la da la implementacion del store, no la disciplina del caller.
 *
 * <p>Deliberadamente no incluye {@code remove}, {@code clear} ni {@code size}:
 * ningun caso de uso actual los requiere. Agregarlos ahora seria abstraccion
 * especulativa (ver DESIGN.md, seccion 4).
 */
public interface BucketStore {

    /**
     * Devuelve el bucket existente del cliente, o crea uno nuevo (lleno, segun
     * {@code config}) si es la primera vez que se ve a ese cliente. La creacion
     * y la busqueda ocurren de forma atomica respecto de otros threads.
     */
    Bucket getOrCreate(String clientId, RateLimiterConfig config);
}
