package com.franchiapello.ratelimiter.domain;

/**
 * Resultado de evaluar si una request puede proceder. Es un objeto de resultado,
 * no una excepcion: un rechazo de rate limit es uno de los dos resultados
 * esperados de la operacion, no una condicion excepcional. Modelarlo como
 * excepcion forzaria try/catch para una rama de negocio normal y complicaria
 * los tests sin necesidad (ver DESIGN.md, seccion 3).
 *
 * <p>Los factory methods {@link #allow} y {@link #deny} existen para que ningun
 * caller pueda construir un estado inconsistente, por ejemplo {@code allowed=true}
 * con {@code retryAfterMillis > 0}, que no tiene sentido de negocio.
 */
public record RateLimitResult(boolean allowed, long remainingTokens, long retryAfterMillis) {

    public static RateLimitResult allow(long remainingTokens) {
        return new RateLimitResult(true, remainingTokens, 0);
    }

    public static RateLimitResult deny(long retryAfterMillis) {
        return new RateLimitResult(false, 0, retryAfterMillis);
    }
}
