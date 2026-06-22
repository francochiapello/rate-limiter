package com.franchiapello.ratelimiter.domain;

/**
 * Puerto que resuelve la {@link RateLimiterConfig} aplicable a un cliente.
 * Existe para permitir que distintos clientes tengan distintos limites
 * (ej. un cliente premium con mas capacidad que uno gratuito) sin que
 * {@code TokenBucketRateLimiter} conozca de donde viene esa politica ni como
 * se decide.
 *
 * <p>Esto NO es lo mismo que "multiples politicas por cliente" (varias reglas
 * simultaneas para el mismo cliente, ej. 100/min Y 1000/hora a la vez), que se
 * descarto explicitamente por complejidad (ver DESIGN.md, seccion 1, decision 5,
 * y seccion 7.2). Esto es una unica regla por cliente, pero potencialmente
 * distinta segun quien sea el cliente.
 *
 * <p>El contrato garantiza que {@link #resolve} siempre devuelve una config
 * valida, nunca null y nunca lanza si el cliente es desconocido: la
 * implementacion concreta decide que politica default aplicar en ese caso.
 * El dominio no necesita saber si un cliente cayo en una politica explicita
 * o en el default (ver DESIGN.md, seccion correspondiente).
 */
public interface RateLimiterConfigProvider {

    RateLimiterConfig resolve(String clientId);
}
