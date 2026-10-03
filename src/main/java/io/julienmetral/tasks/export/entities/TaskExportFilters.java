package io.julienmetral.tasks.export.entities;

import io.julienmetral.tasks.task.entities.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.UUID;

/** The filters of {@code GET /api/v1/tasks}, kept with a tasks export. */
@Embeddable
public record TaskExportFilters(
        @Enumerated(EnumType.STRING)
        @Column(name = "filter_status", length = 50)
        TaskStatus status,

        @Column(name = "filter_assignee_id")
        UUID assigneeId,

        @Column(name = "filter_archived")
        Boolean archived
) {
}
