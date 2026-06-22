package com.franchiapello.ratelimiter.adapter.http;

import com.franchiapello.ratelimiter.domain.RateLimitResult;
import com.franchiapello.ratelimiter.domain.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests del adaptador HTTP usando {@code @WebMvcTest}: levanta unicamente la
 * capa web de Spring, mockeando {@link RateLimiter}. El objetivo es verificar
 * el mapeo (status code, headers, body), no el algoritmo de rate limiting en
 * si — eso ya esta cubierto por los tests del dominio.
 */
@WebMvcTest(RateLimitController.class)
class RateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RateLimiter rateLimiter;

    @Test
    void allowedRequestReturns200WithRemainingTokensHeaderAndBody() throws Exception {
        when(rateLimiter.tryAcquire("client-1")).thenReturn(RateLimitResult.allow(42));

        mockMvc.perform(post("/api/rate-limit/check").header("X-Client-Id", "client-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "42"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remainingTokens").value(42));
    }

    @Test
    void deniedRequestReturns429WithRetryAfterHeaderAndBody() throws Exception {
        when(rateLimiter.tryAcquire("client-2")).thenReturn(RateLimitResult.deny(2500));

        mockMvc.perform(post("/api/rate-limit/check").header("X-Client-Id", "client-2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("Retry-After", "2")) // 2500ms -> 2s (truncado)
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.retryAfterMillis").value(2500));
    }

    @Test
    void deniedRequestWithSubSecondRetryReportsMinimumOneSecond() throws Exception {
        // Caso limite: si faltan 200ms, Retry-After no deberia redondear a 0
        // (eso le diria al cliente "reintenta inmediatamente", que es
        // informacion inutil). Debe reportar como minimo 1 segundo.
        when(rateLimiter.tryAcquire("client-3")).thenReturn(RateLimitResult.deny(200));

        mockMvc.perform(post("/api/rate-limit/check").header("X-Client-Id", "client-3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void missingClientIdHeaderReturns400() throws Exception {
        mockMvc.perform(post("/api/rate-limit/check"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allowedResponseDoesNotIncludeRetryAfterHeader() throws Exception {
        when(rateLimiter.tryAcquire("client-4")).thenReturn(RateLimitResult.allow(10));

        mockMvc.perform(post("/api/rate-limit/check").header("X-Client-Id", "client-4"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"));
    }
}
