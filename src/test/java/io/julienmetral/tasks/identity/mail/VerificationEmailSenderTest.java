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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VerificationEmailSenderTest {

    private static final String VERIFY_URL = "https://app.example.com/verify-email";

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-02T03:04:05Z");

    private static final EmailVerificationRequested EVENT = new EmailVerificationRequested(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "jane@example.com",
            "Jane Doe",
            "the-raw-token",
            EXPIRES_AT
    );

    @Mock
    private MailService mailService;

    private VerificationEmailSender sender;

    @BeforeEach
    void setUp() {
        sender = new VerificationEmailSender(
                mailService,
                new EmailVerificationProperties(Duration.ofHours(24), VERIFY_URL)
        );
    }

    @Test
    void sendHandsVerificationEmailToMailService() {
        sender.send(EVENT);

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(captor.capture());
        MailMessage message = captor.getValue();

        assertThat(message.to()).isEqualTo("jane@example.com");
        assertThat(message.subject()).isEqualTo("Verify your email address");
        assertThat(message.text())
                .startsWith("Hello Jane Doe,")
                .contains(VERIFY_URL + "?token=the-raw-token")
                .contains("The link expires at " + EXPIRES_AT + ".");
    }
}
