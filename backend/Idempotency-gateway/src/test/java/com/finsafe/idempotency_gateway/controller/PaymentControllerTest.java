package com.finsafe.idempotency_gateway.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@SpringBootTest
class PaymentControllerTest {

    private static final String PAYMENT_100 = "{\"amount\": 100, \"currency\": \"GHS\"}";
    private static final String PAYMENT_500 = "{\"amount\": 500, \"currency\": \"GHS\"}";
    private static final String PAYMENT_100_REORDERED = "{\"currency\": \"GHS\", \"amount\": 100.00}";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // User Story 1: first request is processed
    @Test
    void firstRequest_isProcessedAndReturns201() throws Exception {
        long start = System.currentTimeMillis();

        pay(newKey(), PAYMENT_100)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Charged 100 GHS"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(header().doesNotExist("X-Cache-Hit"));

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isGreaterThanOrEqualTo(2000);
    }

    // User Story 2: duplicate request is replayed, not processed again
    @Test
    void duplicateRequest_returnsSameResponseInstantlyWithCacheHitHeader() throws Exception {
        String key = newKey();

        String firstBody = pay(key, PAYMENT_100)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long start = System.currentTimeMillis();
        String secondBody = pay(key, PAYMENT_100)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Cache-Hit", "true"))
                .andReturn().getResponse().getContentAsString();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(secondBody).isEqualTo(firstBody);
        assertThat(elapsed).isLessThan(1000);
    }

    // Design decision: same payment written differently counts as the same request
    @Test
    void reorderedJsonWithSameValues_isTreatedAsDuplicate() throws Exception {
        String key = newKey();
        pay(key, PAYMENT_100).andExpect(status().isCreated());

        pay(key, PAYMENT_100_REORDERED)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Cache-Hit", "true"));
    }

    // User Story 3: same key, different body is rejected
    @Test
    void sameKeyWithDifferentBody_returns422() throws Exception {
        String key = newKey();
        pay(key, PAYMENT_100).andExpect(status().isCreated());

        pay(key, PAYMENT_500)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency key already used for a different request body."));
    }

    // Error handling: missing header
    @Test
    void missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/process-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required."));
    }

    // Error handling: invalid amount
    @Test
    void negativeAmount_returns400() throws Exception {
        pay(newKey(), "{\"amount\": -50, \"currency\": \"GHS\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("amount: must be greater than 0"));
    }

    // Error handling: broken JSON
    @Test
    void invalidJson_returns400() throws Exception {
        pay(newKey(), "{\"amount\": 100, \"currency\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or is not valid JSON."));
    }

    // A rejected request must not use up the key
    @Test
    void invalidRequest_doesNotConsumeKey() throws Exception {
        String key = newKey();
        pay(key, "{\"amount\": -50, \"currency\": \"GHS\"}").andExpect(status().isBadRequest());

        pay(key, PAYMENT_100)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("X-Cache-Hit"));
    }
    // Bonus: two identical requests at the same time -> charged once, B waits for A
    @Test
    void concurrentRequestsWithSameKey_areChargedOnlyOnce() throws Exception {
        String key = newKey();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);

        Callable<MvcResult> sendPayment = () -> {
            startTogether.await();
            return pay(key, PAYMENT_100).andReturn();
        };

        Future<MvcResult> requestA = executor.submit(sendPayment);
        Future<MvcResult> requestB = executor.submit(sendPayment);

        long start = System.currentTimeMillis();
        startTogether.countDown(); // release both requests at the same moment

        MvcResult resultA = requestA.get(10, TimeUnit.SECONDS);
        MvcResult resultB = requestB.get(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();

        // Both succeed with 201 (B does NOT get a 409)
        assertThat(resultA.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultB.getResponse().getStatus()).isEqualTo(201);

        // Same body = same transactionId = charged only once
        assertThat(resultA.getResponse().getContentAsString())
                .isEqualTo(resultB.getResponse().getContentAsString());

        // Exactly one of them was a replay
        long replays = Stream.of(resultA, resultB)
                .filter(r -> "true".equals(r.getResponse().getHeader("X-Cache-Hit")))
                .count();
        assertThat(replays).isEqualTo(1);

        // ~2s total, not ~4s (the payment was not processed twice)
        assertThat(elapsed).isLessThan(3500);
    }
    // ---------- helpers ----------

    private ResultActions pay(String key, String json) throws Exception {
        return mockMvc.perform(post("/process-payment")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String newKey() {
        return "test-" + UUID.randomUUID();
    }
}