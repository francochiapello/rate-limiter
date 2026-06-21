package com.franchiapello.ratelimiter.adapter.http;

import com.franchiapello.ratelimiter.domain.RateLimitResult;

/**
 * Representacion HTTP de un {@link RateLimitResult}. Existe para que el
 * dominio no tenga que conocer como se serializa a JSON (nombres de campo,
 * anotaciones de Jackson, etc.) — esa es una decision de la capa de
 * transporte, no del dominio.
 */
public record RateLimitCheckResponse(boolean allowed, long remainingTokens, long retryAfterMillis) {

    public static RateLimitCheckResponse from(RateLimitResult result) {
        return new RateLimitCheckResponse(
                result.allowed(),
                result.remainingTokens(),
                result.retryAfterMillis());
    }
}
