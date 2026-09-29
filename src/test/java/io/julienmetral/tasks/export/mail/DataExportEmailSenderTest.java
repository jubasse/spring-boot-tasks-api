package io.julienmetral.tasks.export.mail;

import io.julienmetral.tasks.export.ExportProperties;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.events.DataExportCompleted;
import io.julienmetral.tasks.export.events.DataExportFailed;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.repositories.UserRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportEmailSenderTest {

    private static final String DOWNLOAD_PAGE = "https://app.example/exports";

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID EXPORT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-08T10:00:00Z");

    @Mock
    private MailService mailService;

    @Mock
    private UserRepository userRepository;

    private DataExportEmailSender sender;

    @BeforeEach
    void setUp() {
        ExportProperties properties = new ExportProperties(
                DOWNLOAD_PAGE,
                Duration.ofDays(7),
                Duration.ofDays(30),
                500,
                Duration.ofMinutes(15),
                3,
                false,
                "0 30 4 * * *",
                false,
                Duration.ofMinutes(5)
        );
        sender = new DataExportEmailSender(mailService, userRepository, properties);
    }

    private void ownerExists() {
        User owner = new User();
        owner.setEmail("jane@example.com");
        owner.setDisplayName("Jane Doe");
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
    }

    private MailMessage sentMessage() {
        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(message.capture());
        return message.getValue();
    }

    @Test
    void readyEmailLinksToTheDownloadPageOfTheExportAndGivesItsExpiry() {
        ownerExists();

        sender.onCompleted(new DataExportCompleted(EXPORT_ID, OWNER_ID, DataExportType.TASKS_CSV, EXPIRES_AT));

        MailMessage message = sentMessage();
        assertThat(message.to()).isEqualTo("jane@example.com");
        assertThat(message.subject()).isEqualTo("Your export is ready");
        assertThat(message.text())
                .startsWith("Hello Jane Doe,")
                .contains("Your export of tasks is ready. Download it from this page:")
                .contains(DOWNLOAD_PAGE + "?id=" + EXPORT_ID)
                .contains("It stays available until 2030-01-08T10:00:00Z, then it is deleted.");
    }

    @Test
    void readyEmailOfAUsersExportNamesTheUsers() {
        ownerExists();

        sender.onCompleted(new DataExportCompleted(EXPORT_ID, OWNER_ID, DataExportType.USERS_CSV, EXPIRES_AT));

        assertThat(sentMessage().text()).contains("Your export of users is ready.");
    }

    @Test
    void failedEmailAsksToTryAgainAndGivesTheReferenceToQuote() {
        ownerExists();

        sender.onFailed(new DataExportFailed(EXPORT_ID, OWNER_ID, DataExportType.USERS_CSV));

        MailMessage message = sentMessage();
        assertThat(message.to()).isEqualTo("jane@example.com");
        assertThat(message.subject()).isEqualTo("Your export could not be produced");
        assertThat(message.text())
                .startsWith("Hello Jane Doe,")
                .contains("Your export of users could not be produced. Please ask for it again;")
                .contains("contact your administrator with this reference: " + EXPORT_ID + ".");
    }

    @Test
    void ownerWhoseAccountIsGoneIsNotEmailed() {
        sender.onCompleted(new DataExportCompleted(EXPORT_ID, OWNER_ID, DataExportType.TASKS_CSV, EXPIRES_AT));
        sender.onFailed(new DataExportFailed(EXPORT_ID, OWNER_ID, DataExportType.TASKS_CSV));

        verifyNoInteractions(mailService);
    }
}
