package com.finsafe.idempotency_gateway.model;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String transactionId,
        String status,
        BigDecimal amount,
        String currency,
        String message,
        Instant processedAt) {
}