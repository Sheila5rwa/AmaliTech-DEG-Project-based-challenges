package com.finsafe.idempotency_gateway.controller;

import com.finsafe.idempotency_gateway.model.PaymentRequest;
import com.finsafe.idempotency_gateway.model.PaymentResponse;
import com.finsafe.idempotency_gateway.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/process-payment")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentRequest request) {

        PaymentResponse response = paymentService.charge(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}