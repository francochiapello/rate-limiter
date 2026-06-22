package com.franchiapello.ratelimiter.infrastructure;

import com.franchiapello.ratelimiter.domain.RateLimiterConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryRateLimiterConfigProviderTest {

    private final RateLimiterConfig premiumConfig = RateLimiterConfig.of(1000, 100, Duration.ofMinutes(1));
    private final RateLimiterConfig defaultConfig = RateLimiterConfig.of(100, 10, Duration.ofMinutes(1));

    @Test
    void resolvesExplicitConfigForRegisteredClient() {
        InMemoryRateLimiterConfigProvider provider = new InMemoryRateLimiterConfigProvider(
                Map.of("premium-client", premiumConfig),
                defaultConfig);

        RateLimiterConfig resolved = provider.resolve("premium-client");

        assertEquals(premiumConfig, resolved);
    }

    @Test
    void resolvesDefaultConfigForUnknownClient() {
        InMemoryRateLimiterConfigProvider provider = new InMemoryRateLimiterConfigProvider(
                Map.of("premium-client", premiumConfig),
                defaultConfig);

        RateLimiterConfig resolved = provider.resolve("never-seen-before-client");

        assertEquals(defaultConfig, resolved);
    }

    @Test
    void resolvesDefaultConfigWhenNoExplicitConfigsAreRegistered() {
        InMemoryRateLimiterConfigProvider provider = new InMemoryRateLimiterConfigProvider(
                Map.of(),
                defaultConfig);

        RateLimiterConfig resolved = provider.resolve("any-client");

        assertEquals(defaultConfig, resolved);
    }
}
