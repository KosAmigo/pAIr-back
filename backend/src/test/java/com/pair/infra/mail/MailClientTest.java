package com.pair.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

class MailClientTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final MailClient client = new MailClient(sender);

    @Test
    void buildsMimeWithFromToSubjectAndBodies() throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        client.send("from@pair.test", "to@x.com", "[pAIr] 제목", "글자 본문", "<p>HTML 본문</p>");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage m = sent.getValue();
        m.saveChanges();
        assertThat(m.getFrom()[0].toString()).isEqualTo("from@pair.test");
        assertThat(m.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("to@x.com");
        assertThat(m.getSubject()).isEqualTo("[pAIr] 제목");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        m.writeTo(out);
        String raw = out.toString("ISO-8859-1");
        assertThat(raw).contains("multipart/related").contains("text/plain").contains("text/html").contains("Content-ID: <logo>");
    }

    @Test
    void messagingExceptionBecomesMailPreparationException() {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        assertThatThrownBy(() -> client.send("not an address <<", "to@x.com", "s", "t", "h"))
                .isInstanceOf(MailPreparationException.class);
    }

    @Test
    void sendFailureIsRethrownAsIs() {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        MailSendException boom = new MailSendException("boom");
        doThrow(boom).when(sender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> client.send("from@pair.test", "to@x.com", "s", "t", "h")).isSameAs(boom);
    }
}
