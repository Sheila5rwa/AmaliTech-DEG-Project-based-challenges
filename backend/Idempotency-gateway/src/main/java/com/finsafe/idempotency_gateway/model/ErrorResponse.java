package com.finsafe.idempotency_gateway.model;

public record ErrorResponse(
        int status,
        String error,
        String message) {
}