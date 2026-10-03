package io.julienmetral.tasks.export.entities;

public enum DataExportType {
    TASKS_CSV,
    USERS_CSV,
    // Everything the API holds about the caller (GDPR articles 15 and 20): JSON and PDF in a ZIP
    PERSONAL_DATA
}
