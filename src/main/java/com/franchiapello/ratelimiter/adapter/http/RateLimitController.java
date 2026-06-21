package com.franchiapello.ratelimiter.adapter.http;

import com.franchiapello.ratelimiter.domain.RateLimitResult;
import com.franchiapello.ratelimiter.domain.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador HTTP sobre {@link RateLimiter}. No contiene logica de negocio:
 * extrae el {@code clientId} del header, delega en el dominio, y traduce el
 * resultado a status code + headers + body. Cualquier decision sobre tokens
 * o tiempos vive en el dominio, no aca (ver DESIGN.md, seccion 6).
 *
 * <p>El {@code clientId} se recibe por header {@code X-Client-Id} en vez de
 * path o query param: es metadata sobre quien hace la llamada, no parte del
 * recurso solicitado ni un filtro opcional.
 *
 * <p>El endpoint es deliberadamente una demo explicita del rate limiter
 * ({@code POST /api/rate-limit/check}), no un endpoint de negocio simulado
 * (ej. un "ping" inventado): no tiene sentido fingir un dominio de negocio
 * falso solo para tener algo que envolver.
 */
@RestController
public class RateLimitController {

    private static final String CLIENT_ID_HEADER = "X-Client-Id";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimiter rateLimiter;

    public RateLimitController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/rate-limit/check")
    public ResponseEntity<RateLimitCheckResponse> check(
            @RequestHeader(CLIENT_ID_HEADER) String clientId) {

        RateLimitResult result = rateLimiter.tryAcquire(clientId);
        RateLimitCheckResponse body = RateLimitCheckResponse.from(result);

        HttpStatus status = result.allowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS;

        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .header(REMAINING_HEADER, String.valueOf(result.remainingTokens()));

        if (!result.allowed()) {
            long retryAfterSeconds = Math.max(1, result.retryAfterMillis() / 1000);
            response = response.header(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
        }

        return response.body(body);
    }
}
