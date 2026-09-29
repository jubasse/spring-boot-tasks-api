package io.julienmetral.tasks.export.mail;

import io.julienmetral.tasks.export.ExportProperties;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.events.DataExportCompleted;
import io.julienmetral.tasks.export.events.DataExportFailed;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the owner when an export is ready, with a link to the page that downloads it, never the download link
 * itself: that one expires within minutes. Runs in the transaction of the run, so the email leaves once it commits.
 */
@Component
@RequiredArgsConstructor
public class DataExportEmailSender {

    private final MailService mailService;
    private final UserRepository userRepository;
    private final ExportProperties properties;

    @EventListener
    public void onCompleted(DataExportCompleted event) {
        userRepository.findById(event.ownerId()).ifPresent(owner -> mailService.send(new MailMessage(
                owner.getEmail(),
                "Your export is ready",
                """
                        Hello %s,

                        Your export of %s is ready. Download it from this page:
                        %s?id=%s

                        It stays available until %s, then it is deleted.
                        """.formatted(
                        owner.getDisplayName(),
                        subject(event.type()),
                        properties.downloadPageUrl(),
                        event.exportId(),
                        event.expiresAt()
                )
        )));
    }

    @EventListener
    public void onFailed(DataExportFailed event) {
        userRepository.findById(event.ownerId()).ifPresent(owner -> mailService.send(new MailMessage(
                owner.getEmail(),
                "Your export could not be produced",
                """
                        Hello %s,

                        Your export of %s could not be produced. Please ask for it again; if it keeps failing, \
                        contact your administrator with this reference: %s.
                        """.formatted(owner.getDisplayName(), subject(event.type()), event.exportId())
        )));
    }

    private static String subject(DataExportType type) {
        return switch (type) {
            case TASKS_CSV -> "tasks";
            case USERS_CSV -> "users";
        };
    }
}
