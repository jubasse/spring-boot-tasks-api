package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.config.UserRetentionProperties;
import io.julienmetral.tasks.identity.mail.InactiveAccountWarned;
import io.julienmetral.tasks.identity.repositories.UserRetentionQueries;
import io.julienmetral.tasks.identity.repositories.UserRetentionQueries.InactiveUser;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserRetentionService {

    private final UserRetentionQueries queries;
    private final UserService userService;
    private final UserRetentionProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Anonymizes users deleted for longer than {@code anonymize-after}, warns accounts inactive for
     * {@code inactivity-period} (email after commit), and deletes the warned accounts still inactive after
     * {@code deletion-notice}, which revokes their sessions. Deleted accounts are anonymized by a later run.
     */
    @Transactional
    public UserRetentionReport apply() {
        // Several instances may run the schedule: only the one holding the lock works
        if (!queries.tryLock()) {
            return UserRetentionReport.skippedRun();
        }

        Instant now = clock.instant();

        List<UUID> anonymized = queries.anonymizeUsersDeletedBefore(now.minus(properties.anonymizeAfter()), now);

        List<UUID> expired = queries.usersWarnedBefore(now.minus(properties.deletionNotice()));

        expired.forEach(userService::delete);

        List<InactiveUser> warned = queries.warnUsersInactiveSince(now.minus(properties.inactivityPeriod()), now);
        Instant deletionAt = now.plus(properties.deletionNotice());

        warned.forEach(user -> eventPublisher.publishEvent(
                new InactiveAccountWarned(user.email(), user.displayName(), deletionAt)
        ));

        return new UserRetentionReport(false, anonymized.size(), warned.size(), expired.size());
    }
}
