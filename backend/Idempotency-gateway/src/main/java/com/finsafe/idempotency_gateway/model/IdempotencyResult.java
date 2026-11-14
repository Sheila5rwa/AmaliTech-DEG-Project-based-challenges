package com.finsafe.idempotency_gateway.model;

public record IdempotencyResult(
        int statusCode,
        PaymentResponse response,
        boolean replayed) {
}