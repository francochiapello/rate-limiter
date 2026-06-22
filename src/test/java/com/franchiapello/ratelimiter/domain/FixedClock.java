package com.franchiapello.ratelimiter.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * {@link Clock} de prueba cuyo valor se controla manualmente con
 * {@link #advanceMillis}. Existe para testear {@link Bucket#tryConsume} bajo
 * el paso del tiempo sin {@code Thread.sleep} ni dependencia del reloj real
 * (ver DESIGN.md, seccion de testing).
 *
 * <p>Se escribe como clase real en vez de mockear {@link Clock} con Mockito:
 * Clock expone varios metodos (instant, getZone, withZone, millis) y
 * mockearlos todos para este caso de uso (un valor controlable que avanza)
 * es mas ceremonia que esta implementacion minima.
 */
final class FixedClock extends Clock {

    private long millis;

    private FixedClock(long millis) {
        this.millis = millis;
    }

    static FixedClock at(long millis) {
        return new FixedClock(millis);
    }

    void advanceMillis(long delta) {
        this.millis += delta;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("FixedClock no soporta cambio de zona horaria");
    }
}
