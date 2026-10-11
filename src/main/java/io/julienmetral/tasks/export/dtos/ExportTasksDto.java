package io.julienmetral.tasks.export.dtos;

import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.task.entities.TaskStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The filters of {@code GET /api/v1/tasks}; without them, every task that is not archived. */
public record ExportTasksDto(
        @Nullable TaskStatus status, @Nullable UUID assigneeId, @Nullable Boolean archived) {

    public TaskExportFilters filters() {
        return new TaskExportFilters(status, assigneeId, Boolean.TRUE.equals(archived));
    }
}
