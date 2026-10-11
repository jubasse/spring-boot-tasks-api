package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.task.entities.TaskPriority;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

public record UpdateTaskDto(
        @Size(max = 255) @Nullable String title,
        @Nullable String description,
        @Nullable TaskPriority priority,
        @Nullable Instant dueAt) {

    public UpdateTaskDto {
        title = StringUtils.hasText(title) ? title.trim() : null;
        description = StringUtils.hasText(description) ? description.trim() : null;
    }
}
