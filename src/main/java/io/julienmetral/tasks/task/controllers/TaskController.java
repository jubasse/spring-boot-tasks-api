package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.shared.openapi.DocumentedProblems;
import io.julienmetral.tasks.shared.security.AdminOnly;
import io.julienmetral.tasks.task.dtos.*;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskStatus;
import io.julienmetral.tasks.task.security.AllowedRolesOrAssignedToOnly;
import io.julienmetral.tasks.task.security.AllowedRolesOrWithoutAssigneeOnly;
import io.julienmetral.tasks.task.services.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Tag(name = "Tasks", description = "Tasks, their status, assignee and archive.")
public class TaskController {

    private final TaskService taskService;
    private final MediaUrls mediaUrls;

    @Operation(summary = "Create a task")
    @ResponseStatus(HttpStatus.CREATED)
    @DocumentedProblems({ProblemType.REFERENCE_TAKEN, ProblemType.ASSIGNEE_NOT_ACTIVE})
    @PostMapping
    @AllowedRolesOrWithoutAssigneeOnly(UserRole.ADMIN)
    public ResponseEntity<TaskResponseDto> createTask(
            @Valid @RequestBody CreateTaskDto dto
    ) {
        Task task = taskService.create(dto);

        return ResponseEntity
            .created(URI.create("/api/v1/tasks/" + task.getId()))
            .body(response(task));
    }

    @Operation(summary = "List tasks, newest first by default")
    @GetMapping
    public PagedModel<TaskResponseDto> listTasks(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(defaultValue = "false") boolean archived,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return new PagedModel<>(
                taskService.findAll(status, assigneeId, archived, pageable).map(this::response)
        );
    }

    @Operation(summary = "Get a task")
    @GetMapping("/{id}")
    public ResponseEntity<TaskResponseDto> getTask(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
            response(taskService.findById(id))
        );
    }

    @Operation(summary = "Update a task")
    @DocumentedProblems(ProblemType.VERSION_CONFLICT)
    @PatchMapping("/{id}")
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskResponseDto> updateTask(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTaskDto dto
    ) {
        return ResponseEntity.ok(
            response(taskService.update(id, dto))
        );
    }

    @Operation(summary = "Change the status of a task")
    @DocumentedProblems(ProblemType.VERSION_CONFLICT)
    @PatchMapping("/{id}/status")
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskResponseDto> changeTaskStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeTaskStatusDto dto
    ) {
        return ResponseEntity.ok(
            response(
                taskService.changeStatus(id, dto.status())
            )
        );
    }

    @Operation(summary = "Assign a task")
    @DocumentedProblems({ProblemType.ASSIGNEE_NOT_ACTIVE, ProblemType.VERSION_CONFLICT})
    @PatchMapping("/{id}/assign")
    @AdminOnly
    public ResponseEntity<TaskResponseDto> assignTask(
            @PathVariable UUID id,
            @Valid @RequestBody AssignTaskDto dto
    ) {
        return ResponseEntity.ok(
            response(
                taskService.assign(id, dto.userId())
            )
        );
    }

    @Operation(summary = "Archive a task")
    @DocumentedProblems(ProblemType.VERSION_CONFLICT)
    @PostMapping("/{id}/archive")
    @AdminOnly
    public ResponseEntity<TaskResponseDto> archiveTask(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
            response(taskService.archive(id))
        );
    }

    @Operation(summary = "Unarchive a task")
    @DocumentedProblems(ProblemType.VERSION_CONFLICT)
    @PostMapping("/{id}/unarchive")
    @AdminOnly
    public ResponseEntity<TaskResponseDto> unarchiveTask(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
            response(taskService.unarchive(id))
        );
    }

    @Operation(summary = "Cancel a task")
    @DocumentedProblems(ProblemType.VERSION_CONFLICT)
    @PostMapping("/{id}/cancel")
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskResponseDto> cancelTask(
            @PathVariable UUID id,
            @Valid @RequestBody CancelTaskDto dto
    ) {
        return ResponseEntity.ok(
            response(
                taskService.cancel(id, dto.reason())
            )
        );
    }

    @Operation(summary = "Delete a task")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DocumentedProblems(ProblemType.VERSION_CONFLICT)
    @DeleteMapping("/{id}")
    @AdminOnly
    public ResponseEntity<Void> deleteTask(
            @PathVariable UUID id
    ) {
        taskService.delete(id);

        return ResponseEntity.noContent().build();
    }

    private TaskResponseDto response(Task entity) {
        return new TaskResponseDto(entity, mediaUrls);
    }
}
