package com.finsafe.idempotency_gateway.model;

import java.time.Instant;

public record IdempotencyRecord(
        PaymentRequest request,
        int statusCode,
        PaymentResponse response,
        Instant createdAt) {
}