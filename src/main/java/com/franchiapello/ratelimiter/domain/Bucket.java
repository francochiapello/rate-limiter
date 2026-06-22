package com.franchiapello.ratelimiter.domain;

import java.time.Clock;

/**
 * Estado de un balde de tokens para un cliente: cuantos tokens tiene disponibles
 * y desde cuando se cuenta el proximo refill. Es mutable y thread-safe; la
 * politica (cuanto, cada cuanto) NO vive aca, se recibe como parametro en
 * {@link #tryConsume} para mantener estado y politica separados (ver DESIGN.md,
 * seccion 5).
 *
 * <p><b>Concurrencia:</b> protegido por un {@link Object} privado, no por
 * {@code synchronized} en la firma del metodo (que sincronizaria sobre
 * {@code this} y expondria el lock a cualquier codigo externo con una
 * referencia a esta instancia). Se eligio lock por bucket en vez de un
 * enfoque lock-free (CAS) porque la diferencia de performance es irrelevante
 * a esta escala y el codigo con lock es mas simple de leer y de testear
 * correctamente (ver DESIGN.md, seccion 5).
 *
 * <p><b>Tiempo:</b> el reloj se inyecta via {@link Clock} en vez de llamar a
 * {@code System.currentTimeMillis()} directamente. Esto es el mismo patron
 * que {@link BucketStore}: una dependencia del mundo exterior (el tiempo, en
 * este caso) se abstrae para poder controlarla en los tests sin
 * {@code Thread.sleep} ni condiciones de carrera con el reloj real. El codigo
 * de produccion no necesita pensar en esto: {@link #newFull(RateLimiterConfig)}
 * usa {@code Clock.systemUTC()} por defecto.
 */
public final class Bucket {

    private final Object lock = new Object();
    private final Clock clock;
    private long availableTokens;
    private long lastRefillTimestampMillis;

    private Bucket(Clock clock, long availableTokens, long lastRefillTimestampMillis) {
        this.clock = clock;
        this.availableTokens = availableTokens;
        this.lastRefillTimestampMillis = lastRefillTimestampMillis;
    }

    /**
     * Crea un bucket nuevo, lleno a su capacidad maxima, usando el reloj del
     * sistema. Es el punto de entrada para codigo de produccion: no requiere
     * que el caller piense en relojes.
     */
    public static Bucket newFull(RateLimiterConfig config) {
        return newFull(config, Clock.systemUTC());
    }

    /**
     * Crea un bucket nuevo, lleno a su capacidad maxima, usando el {@link Clock}
     * dado. Permite controlar el paso del tiempo en los tests.
     */
    public static Bucket newFull(RateLimiterConfig config, Clock clock) {
        return new Bucket(clock, config.capacity(), clock.millis());
    }

    /**
     * Intenta consumir un token. Si hay tokens disponibles (despues de aplicar
     * el refill correspondiente al tiempo transcurrido), lo consume y permite
     * la request. Si no, la rechaza informando cuanto falta para el proximo token.
     */
    public RateLimitResult tryConsume(RateLimiterConfig config) {
        synchronized (lock) {
            refill(config);
            if (availableTokens > 0) {
                availableTokens--;
                return RateLimitResult.allow(availableTokens);
            }
            return RateLimitResult.deny(millisUntilNextToken(config));
        }
    }

    /**
     * Repone tokens segun el tiempo transcurrido desde el ultimo refill.
     *
     * <p>El timestamp avanza en multiplos exactos de {@code refillPeriod}, no se
     * resetea a "ahora". Si se reseteara a {@code now} en cada llamada, las
     * fracciones de periodo que no llegaron a completar un token se perderian
     * en cada calculo, y el bucket se recargaria mas lento de lo que la
     * configuracion indica (drift). Avanzando en multiplos exactos, el tiempo
     * sobrante dentro del periodo actual se conserva para el proximo calculo
     * (ver DESIGN.md, seccion 5).
     */
    private void refill(RateLimiterConfig config) {
        long now = clock.millis();
        long elapsed = now - lastRefillTimestampMillis;
        if (elapsed <= 0) {
            return;
        }
        long periodMillis = config.refillPeriod().toMillis();
        long periodsElapsed = elapsed / periodMillis;
        if (periodsElapsed > 0) {
            long tokensToAdd = periodsElapsed * config.refillTokens();
            availableTokens = Math.min(config.capacity(), availableTokens + tokensToAdd);
            lastRefillTimestampMillis += periodsElapsed * periodMillis;
        }
    }

    /**
     * Estima cuanto falta para que haya al menos un token nuevo disponible.
     * Es una aproximacion: si {@code refillTokens > 1}, no representa cuando
     * el bucket vuelve a estar lleno, sino cuando el siguiente lote de tokens
     * se repone.
     */
    private long millisUntilNextToken(RateLimiterConfig config) {
        long periodMillis = config.refillPeriod().toMillis();
        long elapsedInCurrentPeriod = (clock.millis() - lastRefillTimestampMillis) % periodMillis;
        return periodMillis - elapsedInCurrentPeriod;
    }
}
