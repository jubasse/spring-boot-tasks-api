package io.julienmetral.tasks.identity.mail;

import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class InactiveAccountEmailSenderTest {

    private static final TimeZone JVM_TIME_ZONE = TimeZone.getDefault();

    @Mock
    private MailService mailService;

    @InjectMocks
    private InactiveAccountEmailSender sender;

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(JVM_TIME_ZONE);
    }

    @Test
    void sendHandsTheInactivityWarningToMailService() {
        sender.send(new InactiveAccountWarned("jane@example.com", "Jane Doe", Instant.parse("2030-01-31T04:00:00Z")));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(captor.capture());
        verifyNoMoreInteractions(mailService);
        MailMessage message = captor.getValue();

        assertThat(message.to()).isEqualTo("jane@example.com");
        assertThat(message.subject()).isEqualTo("Your account will be deleted");
        assertThat(message.text())
                .startsWith("Hello Jane Doe,")
                .contains("You have not used your account for a long time.")
                .contains("it will be deleted on 2030-01-31.")
                .contains("To keep it, simply log in before then.");
    }

    // 23:30 UTC is already the next day east of UTC, and 00:30 UTC still the previous day west of it
    @ParameterizedTest
    @ValueSource(strings = {"Pacific/Kiritimati", "America/Los_Angeles", "Europe/Paris", "UTC"})
    void deletionDateIsTheUtcDateWhateverTheJvmTimeZone(String timeZone) {
        TimeZone.setDefault(TimeZone.getTimeZone(timeZone));

        sender.send(new InactiveAccountWarned("late@example.com", "Late", Instant.parse("2030-01-31T23:30:00Z")));
        sender.send(new InactiveAccountWarned("early@example.com", "Early", Instant.parse("2030-02-01T00:30:00Z")));

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(0).text()).contains("deleted on 2030-01-31.");
        assertThat(captor.getAllValues().get(1).text()).contains("deleted on 2030-02-01.");
    }
}
