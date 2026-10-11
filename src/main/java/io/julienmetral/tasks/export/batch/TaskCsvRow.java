package io.julienmetral.tasks.export.batch;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record TaskCsvRow(
        UUID id,
        String reference,
        String title,
        @Nullable String description,
        String status,
        String priority,
        @Nullable String assignee,
        @Nullable String createdBy,
        @Nullable Instant dueAt,
        @Nullable Instant completedAt,
        @Nullable Instant archivedAt,
        Instant createdAt,
        Instant updatedAt) {

    static final List<String> HEADER =
            List.of(
                    "id",
                    "reference",
                    "title",
                    "description",
                    "status",
                    "priority",
                    "assignee",
                    "created_by",
                    "due_at",
                    "completed_at",
                    "archived_at",
                    "created_at",
                    "updated_at");

    List<@Nullable Object> values() {
        return Arrays.asList(
                id,
                reference,
                title,
                description,
                status,
                priority,
                assignee,
                createdBy,
                dueAt,
                completedAt,
                archivedAt,
                createdAt,
                updatedAt);
    }
}
