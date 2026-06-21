package com.franchiapello.ratelimiter.domain;

import java.time.Duration;

/**
 * Politica de rate limiting para un cliente: cuantos tokens caben en el balde
 * (capacity) y cuantos se reponen (refillTokens) cada cuanto tiempo (refillPeriod).
 *
 * <p>Es un {@code record} porque es un value object puro: no tiene identidad propia
 * mas alla de sus valores, es inmutable por naturaleza, y no necesita mas comportamiento
 * que validar su propia consistencia. Un builder seria ceremonia sin beneficio: no hay
 * suficientes campos opcionales como para justificarlo (ver DESIGN.md, seccion 6).
 *
 * <p>Se modela como (capacity, refillTokens, refillPeriod) en vez de una unica
 * "tasa por segundo" para hablar el mismo lenguaje que el negocio usa
 * ("100 de capacidad, se recargan 10 cada 1 segundo") y evitar errores de
 * redondeo al forzar tasas fraccionarias (ej. "5 cada 10 segundos" = 0.5/seg).
 */
public record RateLimiterConfig(long capacity, long refillTokens, Duration refillPeriod) {

    public RateLimiterConfig {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be positive, got: " + refillTokens);
        }
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be positive, got: " + refillPeriod);
        }
    }

    /**
     * Factory method explicito en vez de exponer solo el constructor del record.
     * No cambia el comportamiento, pero deja un punto de entrada legible en los
     * call sites: {@code RateLimiterConfig.of(100, 10, Duration.ofSeconds(1))}.
     */
    public static RateLimiterConfig of(long capacity, long refillTokens, Duration refillPeriod) {
        return new RateLimiterConfig(capacity, refillTokens, refillPeriod);
    }
}
