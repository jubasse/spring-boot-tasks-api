package io.julienmetral.tasks.export.batch;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

record TaskCsvRow(
        UUID id,
        String reference,
        String title,
        String description,
        String status,
        String priority,
        String assignee,
        String createdBy,
        Instant dueAt,
        Instant completedAt,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt
) {

    static final List<String> HEADER = List.of(
            "id", "reference", "title", "description", "status", "priority", "assignee", "created_by",
            "due_at", "completed_at", "archived_at", "created_at", "updated_at"
    );

    List<Object> values() {
        return Arrays.asList(
                id, reference, title, description, status, priority, assignee, createdBy,
                dueAt, completedAt, archivedAt, createdAt, updatedAt
        );
    }
}
