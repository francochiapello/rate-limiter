package com.franchiapello.ratelimiter;

import com.franchiapello.ratelimiter.domain.RateLimiter;
import com.franchiapello.ratelimiter.domain.RateLimiterConfig;
import com.franchiapello.ratelimiter.domain.RateLimiterConfigProvider;
import com.franchiapello.ratelimiter.domain.TokenBucketRateLimiter;
import com.franchiapello.ratelimiter.infrastructure.InMemoryBucketStore;
import com.franchiapello.ratelimiter.infrastructure.InMemoryRateLimiterConfigProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.Map;

/**
 * Punto de entrada de la aplicacion. El unico rol de esta clase es el cableado
 * (wiring) de las dependencias del dominio hacia beans de Spring — ninguna
 * logica de negocio vive aca. El dominio (paquete {@code domain}) no tiene
 * idea de que Spring existe; esta clase es la unica frontera donde eso pasa.
 */
@SpringBootApplication
public class RateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }

    /**
     * Configuracion default para cualquier cliente no registrado
     * explicitamente: 100 requests, reponiendo 10 por segundo.
     *
     * <p>Estos valores son deliberadamente simples y hardcodeados para este
     * prototipo. Externalizarlos a {@code application.yml} seria razonable
     * en un proyecto real, pero no aporta nada al foco de esta evaluacion
     * (el dominio del rate limiter), por lo que se mantiene como la opcion
     * mas simple que cumple el contrato (ver DESIGN.md).
     */
    @Bean
    public RateLimiterConfigProvider rateLimiterConfigProvider() {
        RateLimiterConfig defaultConfig = RateLimiterConfig.of(100, 10, Duration.ofSeconds(1));
        return new InMemoryRateLimiterConfigProvider(Map.of(), defaultConfig);
    }

    @Bean
    public InMemoryBucketStore bucketStore() {
        return new InMemoryBucketStore();
    }

    @Bean
    public RateLimiter rateLimiter(InMemoryBucketStore bucketStore, RateLimiterConfigProvider configProvider) {
        return new TokenBucketRateLimiter(bucketStore, configProvider);
    }
}
