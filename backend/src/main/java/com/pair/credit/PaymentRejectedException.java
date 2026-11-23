package com.pair.credit;

public class PaymentRejectedException extends RuntimeException {
    public PaymentRejectedException(String message) { super(message); }
}
