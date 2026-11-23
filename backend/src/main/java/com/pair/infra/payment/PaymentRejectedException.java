package com.pair.infra.payment;

public class PaymentRejectedException extends RuntimeException {
    public PaymentRejectedException(String message) { super(message); }
}
