package com.finsafe.idempotency_gateway.service;

import com.finsafe.idempotency_gateway.model.PaymentRequest;
import com.finsafe.idempotency_gateway.model.PaymentResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private static final long PROCESSING_DELAY_MS = 2000;

    public PaymentResponse charge(PaymentRequest request) {
        simulateProcessingDelay();

        String message = "Charged " + request.amount().toPlainString() + " " + request.currency();

        return new PaymentResponse(
                "txn_" + UUID.randomUUID(),
                "SUCCESS",
                request.amount(),
                request.currency(),
                message,
                Instant.now());
    }

    private void simulateProcessingDelay() {
        try {
            Thread.sleep(PROCESSING_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment processing was interrupted", e);
        }
    }
}