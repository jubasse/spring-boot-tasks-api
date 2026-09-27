package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.task.dtos.TaskEventResponseDto;
import io.julienmetral.tasks.task.entities.TaskEvent;
import io.julienmetral.tasks.task.services.TaskEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/events")
@RequiredArgsConstructor
@Tag(name = "History", description = "What happened to a task, and who did it.")
public class TaskEventController {

    private final TaskEventService taskEventService;
    private final MediaUrls mediaUrls;

    @Operation(summary = "List the history of a task")
    @GetMapping
    public PagedModel<TaskEventResponseDto> listTaskHistory(
            @PathVariable UUID taskId,
            @ParameterObject
            @PageableDefault(sort = {"occurredAt", "id"}, direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        return new PagedModel<>(taskEventService.findAllByTaskId(taskId, pageable).map(this::response));
    }

    private TaskEventResponseDto response(TaskEvent entity) {
        return new TaskEventResponseDto(entity, mediaUrls);
    }
}
