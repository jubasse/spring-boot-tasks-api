package io.julienmetral.tasks.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MailQueueListenerTest {

    private static final MailMessage MESSAGE = new MailMessage("jane@example.com", "Subject", "Body");

    @Mock
    private JavaMailSender mailSender;

    private MailQueueListener listener;

    @BeforeEach
    void setUp() {
        listener = new MailQueueListener(mailSender, new MailProperties("no-reply@example.com"));
    }

    @Test
    void sendBuildsTheEmailFromTheMessageAndTheConfiguredSender() {
        listener.send(MESSAGE);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage mail = captor.getValue();

        assertThat(mail.getFrom()).isEqualTo("no-reply@example.com");
        assertThat(mail.getTo()).containsExactly("jane@example.com");
        assertThat(mail.getSubject()).isEqualTo("Subject");
        assertThat(mail.getText()).isEqualTo("Body");
    }

    @Test
    void smtpSendFailurePropagatesSoTheContainerRetries() {
        MailSendException failure = new MailSendException("SMTP down");
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> listener.send(MESSAGE)).isSameAs(failure);
    }

    @Test
    void smtpAuthenticationFailurePropagatesSoTheContainerRetries() {
        MailAuthenticationException failure = new MailAuthenticationException("Bad credentials");
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> listener.send(MESSAGE)).isSameAs(failure);
    }

    @Test
    void unparsableEmailIsRejectedWithoutRequeueKeepingTheCause() {
        MailParseException failure = new MailParseException("Invalid address");
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> listener.send(MESSAGE))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Subject")
                .cause().isSameAs(failure);
    }

    @Test
    void unpreparableEmailIsRejectedWithoutRequeueKeepingTheCause() {
        MailPreparationException failure = new MailPreparationException("Template failed");
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> listener.send(MESSAGE))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Subject")
                .cause().isSameAs(failure);
    }
}
