package com.finsafe.idempotency_gateway.controller;

import com.finsafe.idempotency_gateway.exception.InvalidIdempotencyKeyException;
import com.finsafe.idempotency_gateway.model.IdempotencyResult;
import com.finsafe.idempotency_gateway.model.PaymentRequest;
import com.finsafe.idempotency_gateway.model.PaymentResponse;
import com.finsafe.idempotency_gateway.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyService idempotencyService;

    public PaymentController(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/process-payment")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        if (idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key header is required.");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters.");
        }

        IdempotencyResult result = idempotencyService.process(idempotencyKey, request);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.statusCode());
        if (result.replayed()) {
            response.header("X-Cache-Hit", "true");
        }
        return response.body(result.response());
    }
}