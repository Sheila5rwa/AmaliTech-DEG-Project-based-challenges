package com.finsafe.idempotency_gateway.service;

import com.finsafe.idempotency_gateway.exception.IdempotencyKeyConflictException;
import com.finsafe.idempotency_gateway.model.IdempotencyRecord;
import com.finsafe.idempotency_gateway.model.IdempotencyResult;
import com.finsafe.idempotency_gateway.model.PaymentRequest;
import com.finsafe.idempotency_gateway.model.PaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private final PaymentService paymentService;

    // key -> "box" that holds the saved result (or will hold it once processing finishes)
    private final Map<String, CompletableFuture<IdempotencyRecord>> store = new ConcurrentHashMap<>();

    public IdempotencyService(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public IdempotencyResult process(String idempotencyKey, PaymentRequest request) {
        CompletableFuture<IdempotencyRecord> ourFuture = new CompletableFuture<>();

        // Atomic: only ONE request can ever put a box in for this key
        CompletableFuture<IdempotencyRecord> existingFuture = store.putIfAbsent(idempotencyKey, ourFuture);

        if (existingFuture == null) {
            return processFirstRequest(idempotencyKey, request, ourFuture);
        }
        return replayExistingRequest(idempotencyKey, request, existingFuture);
    }

    private IdempotencyResult processFirstRequest(String idempotencyKey,
                                                  PaymentRequest request,
                                                  CompletableFuture<IdempotencyRecord> future) {
        try {
            PaymentResponse response = paymentService.charge(request);
            int statusCode = HttpStatus.CREATED.value();
            IdempotencyRecord record = new IdempotencyRecord(request, statusCode, response, Instant.now());

            future.complete(record); // wakes up anyone waiting
            return new IdempotencyResult(statusCode, response, false);

        } catch (RuntimeException e) {
            // Payment failed: free the key so the client can retry safely
            store.remove(idempotencyKey, future);
            future.completeExceptionally(e);
            throw e;
        }
    }

    private IdempotencyResult replayExistingRequest(String idempotencyKey,
                                                    PaymentRequest request,
                                                    CompletableFuture<IdempotencyRecord> future) {
        IdempotencyRecord record;
        try {
            record = future.join(); // waits here if the first request is still processing
        } catch (CompletionException e) {
            // The first request failed and released the key, so try again from the start
            return process(idempotencyKey, request);
        }

        if (!isSameRequest(record.request(), request)) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency key already used for a different request body.");
        }
        return new IdempotencyResult(record.statusCode(), record.response(), true);
    }

    private boolean isSameRequest(PaymentRequest saved, PaymentRequest incoming) {
        return saved.amount().compareTo(incoming.amount()) == 0
                && saved.currency().equals(incoming.currency());
    }
}