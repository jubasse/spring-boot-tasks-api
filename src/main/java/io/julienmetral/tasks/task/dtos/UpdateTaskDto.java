package io.julienmetral.tasks.task.dtos;

import io.julienmetral.tasks.task.entities.TaskPriority;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.util.StringUtils;

public record UpdateTaskDto(
        @Size(max = 255) String title, String description, TaskPriority priority, Instant dueAt) {

    public UpdateTaskDto {
        title = StringUtils.hasText(title) ? title.trim() : null;
        description = StringUtils.hasText(description) ? description.trim() : null;
    }
}
