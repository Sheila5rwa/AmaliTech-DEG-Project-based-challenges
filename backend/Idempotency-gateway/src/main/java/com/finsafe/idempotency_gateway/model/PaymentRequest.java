package com.finsafe.idempotency_gateway.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PaymentRequest(

        @NotNull(message = "must not be missing")
        @Positive(message = "must be greater than 0")
        BigDecimal amount,

        @NotNull(message = "must not be missing")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter code like GHS")
        String currency) {
}