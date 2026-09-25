package io.julienmetral.tasks.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MailDispatcherTest {

    private static final MailService.MailRequested EVENT = new MailService.MailRequested(
            new MailMessage("jane@example.com", "Subject", "Body")
    );

    @Mock
    private JavaMailSender mailSender;

    private MailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new MailDispatcher(mailSender, new MailProperties("no-reply@example.com"));
    }

    @Test
    void dispatchSendsMessageFromConfiguredSender() {
        dispatcher.dispatch(EVENT);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage mail = captor.getValue();

        assertThat(mail.getFrom()).isEqualTo("no-reply@example.com");
        assertThat(mail.getTo()).containsExactly("jane@example.com");
        assertThat(mail.getSubject()).isEqualTo("Subject");
        assertThat(mail.getText()).isEqualTo("Body");
    }

    @Test
    void dispatchSwallowsMailFailure() {
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> dispatcher.dispatch(EVENT)).doesNotThrowAnyException();
    }

    @Test
    void dispatchDoesNotSwallowOtherFailures() {
        doThrow(new IllegalStateException("boom")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> dispatcher.dispatch(EVENT)).isInstanceOf(IllegalStateException.class);
    }
}
