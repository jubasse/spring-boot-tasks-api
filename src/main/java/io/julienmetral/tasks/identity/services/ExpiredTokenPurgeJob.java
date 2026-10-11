package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.config.ScheduledJobLocks;
import io.julienmetral.tasks.identity.services.ExpiredTokenPurgeService.PurgedTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "identity.token-purge.enabled", matchIfMissing = true)
public class ExpiredTokenPurgeJob {

    private final ExpiredTokenPurgeService purgeService;

    @Scheduled(cron = "${identity.token-purge.cron}")
    @SchedulerLock(
            name = ScheduledJobLocks.EXPIRED_TOKEN_PURGE,
            lockAtMostFor = "PT1H",
            lockAtLeastFor = "PT5M")
    public void purge() {
        PurgedTokens purged = purgeService.purge();

        if (!purged.isEmpty()) {
            log.info(
                    "Expired tokens purged: {} refresh, {} email verification, {} password reset",
                    purged.refreshTokens(),
                    purged.verificationTokens(),
                    purged.resetTokens());
        }
    }
}
