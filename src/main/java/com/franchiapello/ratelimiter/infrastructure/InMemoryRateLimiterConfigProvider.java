package com.franchiapello.ratelimiter.infrastructure;

import com.franchiapello.ratelimiter.domain.RateLimiterConfig;
import com.franchiapello.ratelimiter.domain.RateLimiterConfigProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacion en memoria de {@link RateLimiterConfigProvider}: mantiene un
 * mapa de configuraciones explicitas por clientId, y devuelve una
 * {@code defaultConfig} para cualquier cliente no registrado.
 *
 * <p>El default vive aca, en infraestructura, no en el dominio: que politica
 * aplicar a un cliente desconocido es una decision de configuracion del
 * sistema desplegado, no una regla del algoritmo de rate limiting en si.
 *
 * <p>No es thread-safe por necesidad de este caso de uso especifico (las
 * configuraciones tipicamente se cargan una vez al iniciar la aplicacion),
 * pero se usa {@link ConcurrentHashMap} de todas formas porque
 * {@code resolve} se invoca desde el mismo path concurrente que
 * {@code BucketStore.getOrCreate} (multiples threads, multiples clientes
 * en simultaneo) y no hay razon para introducir una estructura distinta
 * con garantias mas debiles.
 */
public class InMemoryRateLimiterConfigProvider implements RateLimiterConfigProvider {

    private final Map<String, RateLimiterConfig> configsByClientId;
    private final RateLimiterConfig defaultConfig;

    public InMemoryRateLimiterConfigProvider(
            Map<String, RateLimiterConfig> configsByClientId,
            RateLimiterConfig defaultConfig) {
        this.configsByClientId = new ConcurrentHashMap<>(configsByClientId);
        this.defaultConfig = defaultConfig;
    }

    @Override
    public RateLimiterConfig resolve(String clientId) {
        return configsByClientId.getOrDefault(clientId, defaultConfig);
    }
}
