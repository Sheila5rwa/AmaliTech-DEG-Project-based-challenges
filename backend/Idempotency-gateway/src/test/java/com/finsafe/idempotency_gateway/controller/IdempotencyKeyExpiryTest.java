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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs the app with a 1-second TTL so we can watch a key expire
@SpringBootTest(properties = "idempotency.key-ttl-seconds=1")
class IdempotencyKeyExpiryTest {

    private static final String PAYMENT_100 = "{\"amount\": 100, \"currency\": \"GHS\"}";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void expiredKey_isProcessedAsNewRequest() throws Exception {
        String key = "expiry-" + UUID.randomUUID();

        String firstBody = pay(key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("X-Cache-Hit"))
                .andReturn().getResponse().getContentAsString();

        Thread.sleep(1500); // wait longer than the 1-second TTL

        String secondBody = pay(key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("X-Cache-Hit"))
                .andReturn().getResponse().getContentAsString();

        assertThat(secondBody).isNotEqualTo(firstBody); // new transactionId = processed again
    }

    private ResultActions pay(String key) throws Exception {
        return mockMvc.perform(post("/process-payment")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PAYMENT_100));
    }
}