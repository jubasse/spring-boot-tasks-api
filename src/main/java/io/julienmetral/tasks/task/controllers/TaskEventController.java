package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.task.dtos.TaskEventResponseDto;
import io.julienmetral.tasks.task.services.TaskEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/events")
@RequiredArgsConstructor
public class TaskEventController {

    private final TaskEventService taskEventService;

    @GetMapping
    public Page<TaskEventResponseDto> findAll(
            @PathVariable UUID taskId,
            Pageable pageable
    ) {
        return taskEventService
                .findAllByTaskId(taskId, pageable)
                .map(TaskEventResponseDto::new);
    }
}