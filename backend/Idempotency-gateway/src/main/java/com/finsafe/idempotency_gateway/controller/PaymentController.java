package com.finsafe.idempotency_gateway.controller;

import com.finsafe.idempotency_gateway.model.IdempotencyResult;
import com.finsafe.idempotency_gateway.model.PaymentRequest;
import com.finsafe.idempotency_gateway.model.PaymentResponse;
import com.finsafe.idempotency_gateway.service.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final IdempotencyService idempotencyService;

    public PaymentController(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/process-payment")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentRequest request) {

        IdempotencyResult result = idempotencyService.process(idempotencyKey, request);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.statusCode());
        if (result.replayed()) {
            response.header("X-Cache-Hit", "true");
        }
        return response.body(result.response());
    }
}