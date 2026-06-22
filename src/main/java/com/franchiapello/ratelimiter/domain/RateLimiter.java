package com.franchiapello.ratelimiter.domain;

/**
 * Puerto principal del dominio: decide si una request de un cliente puede
 * proceder. Es la unica abstraccion que el resto del sistema (ej. el
 * adaptador HTTP) necesita conocer; no sabe ni le importa que algoritmo
 * hay detras (Token Bucket hoy, podria ser otro mañana sin que el caller
 * se entere).
 */
public interface RateLimiter {

    RateLimitResult tryAcquire(String clientId);
}
