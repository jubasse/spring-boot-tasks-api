package io.julienmetral.tasks.export.entities;

import java.util.Set;

public enum DataExportStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    // Completed, then its file deleted after the retention period
    EXPIRED;

    public static final Set<DataExportStatus> ACTIVE = Set.of(QUEUED, RUNNING);
}
