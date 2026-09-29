package io.julienmetral.tasks.config;

import java.util.List;

/**
 * Names of the rows in {@code scheduler_locks}, one per job that must run on a single instance at a time.
 * Warning: renaming a lock lets one run happen twice while a rolling deploy mixes the old and new names.
 */
public final class ScheduledJobLocks {

    public static final String DATA_EXPORT_PURGE = "data-export-purge";
    public static final String DATA_EXPORT_RECOVERY = "data-export-recovery";
    public static final String MEDIA_CLEANUP = "media-cleanup";
    public static final String OUTBOX_PURGE = "outbox-purge";
    public static final String RATE_LIMIT_PURGE = "rate-limit-purge";
    public static final String TASK_REMINDERS = "task-reminders";
    public static final String USER_RETENTION = "user-retention";
    public static final String WEBHOOK_DELIVERY_PURGE = "webhook-delivery-purge";

    /** Registered with the metrics at startup, so each lock has its series before its first run. */
    public static final List<String> NAMES = List.of(
            DATA_EXPORT_PURGE,
            DATA_EXPORT_RECOVERY,
            MEDIA_CLEANUP,
            OUTBOX_PURGE,
            RATE_LIMIT_PURGE,
            TASK_REMINDERS,
            USER_RETENTION,
            WEBHOOK_DELIVERY_PURGE
    );

    private ScheduledJobLocks() {
    }
}
