package com.pair.infra.mail;

import org.springframework.mail.MailException;

public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(MailException cause) {
        super(cause);
    }
}
