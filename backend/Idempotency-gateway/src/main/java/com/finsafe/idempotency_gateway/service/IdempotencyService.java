package com.finsafe.idempotency_gateway.service;

import com.finsafe.idempotency_gateway.exception.IdempotencyKeyConflictException;
import com.finsafe.idempotency_gateway.model.IdempotencyRecord;
import com.finsafe.idempotency_gateway.model.IdempotencyResult;
import com.finsafe.idempotency_gateway.model.PaymentRequest;
import com.finsafe.idempotency_gateway.model.PaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private final PaymentService paymentService;
    private final Duration keyTtl;

    // key -> "box" that holds the saved result (or will hold it once processing finishes)
    private final Map<String, CompletableFuture<IdempotencyRecord>> store = new ConcurrentHashMap<>();

    public IdempotencyService(PaymentService paymentService,
                              @Value("${idempotency.key-ttl-seconds:86400}") long keyTtlSeconds) {
        this.paymentService = paymentService;
        this.keyTtl = Duration.ofSeconds(keyTtlSeconds);
    }

    public IdempotencyResult process(String idempotencyKey, PaymentRequest request) {
        // Developer's Choice: forget keys older than the TTL
        CompletableFuture<IdempotencyRecord> current = store.get(idempotencyKey);
        if (current != null && isExpired(current)) {
            store.remove(idempotencyKey, current);
        }

        CompletableFuture<IdempotencyRecord> ourFuture = new CompletableFuture<>();

        // Atomic: only ONE request can ever put a box in for this key
        CompletableFuture<IdempotencyRecord> existingFuture = store.putIfAbsent(idempotencyKey, ourFuture);

        if (existingFuture == null) {
            return processFirstRequest(idempotencyKey, request, ourFuture);
        }
        return replayExistingRequest(idempotencyKey, request, existingFuture);
    }

    // Developer's Choice: every 60 seconds, remove expired keys so memory doesn't grow forever
    @Scheduled(fixedRate = 60_000)
    public void removeExpiredKeys() {
        store.entrySet().removeIf(entry -> isExpired(entry.getValue()));
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

    // Only finished, successful entries can expire; in-flight ones never do
    private boolean isExpired(CompletableFuture<IdempotencyRecord> future) {
        if (!future.isDone() || future.isCompletedExceptionally()) {
            return false;
        }
        Instant createdAt = future.join().createdAt();
        return createdAt.plus(keyTtl).isBefore(Instant.now());
    }

    private boolean isSameRequest(PaymentRequest saved, PaymentRequest incoming) {
        return saved.amount().compareTo(incoming.amount()) == 0
                && saved.currency().equals(incoming.currency());
    }
}