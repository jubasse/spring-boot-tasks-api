package io.julienmetral.tasks.identity.mail;

import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class PasswordResetEmailSenderTest {

    private static final String RESET_URL = "https://app.example.com/reset-password";

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-02T03:04:05Z");

    private static final PasswordResetRequested EVENT = new PasswordResetRequested(
            "jane@example.com",
            "Jane Doe",
            "the-raw-token",
            EXPIRES_AT
    );

    @Mock
    private MailService mailService;

    private PasswordResetEmailSender sender;

    @BeforeEach
    void setUp() {
        sender = new PasswordResetEmailSender(
                mailService,
                new PasswordResetProperties(Duration.ofHours(1), RESET_URL)
        );
    }

    @Test
    void sendHandsPasswordResetEmailToMailService() {
        sender.send(EVENT);

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(captor.capture());
        verifyNoMoreInteractions(mailService);
        MailMessage message = captor.getValue();

        assertThat(message.to()).isEqualTo("jane@example.com");
        assertThat(message.subject()).isEqualTo("Reset your password");
        assertThat(message.text())
                .startsWith("Hello Jane Doe,")
                .contains(RESET_URL + "?token=the-raw-token\n")
                .contains("The link expires at " + EXPIRES_AT + " and can be used once.")
                .contains("signs you out everywhere")
                .contains("If you did not ask for this");
    }
}
