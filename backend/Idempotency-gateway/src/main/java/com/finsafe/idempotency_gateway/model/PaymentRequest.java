package com.finsafe.idempotency_gateway.model;

import java.math.BigDecimal;

public record PaymentRequest(BigDecimal amount, String currency) {
}