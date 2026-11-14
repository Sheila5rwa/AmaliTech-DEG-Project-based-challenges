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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private final PaymentService paymentService;
    private final Map<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    public IdempotencyService(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public IdempotencyResult process(String idempotencyKey, PaymentRequest request) {
        IdempotencyRecord existing = store.get(idempotencyKey);

        if (existing != null) {
            if (!isSameRequest(existing.request(), request)) {
                throw new IdempotencyKeyConflictException(
                        "Idempotency key already used for a different request body.");
            }
            return new IdempotencyResult(existing.statusCode(), existing.response(), true);
        }

        PaymentResponse response = paymentService.charge(request);
        int statusCode = HttpStatus.CREATED.value();
        store.put(idempotencyKey, new IdempotencyRecord(request, statusCode, response, Instant.now()));
        return new IdempotencyResult(statusCode, response, false);
    }

    private boolean isSameRequest(PaymentRequest saved, PaymentRequest incoming) {
        return saved.amount().compareTo(incoming.amount()) == 0
                && saved.currency().equals(incoming.currency());
    }
}