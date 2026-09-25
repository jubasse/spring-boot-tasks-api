package io.julienmetral.tasks.task.controllers;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.shared.security.AdminOnly;
import io.julienmetral.tasks.task.dtos.*;
import io.julienmetral.tasks.task.entities.Task;
import io.julienmetral.tasks.task.entities.TaskStatus;
import io.julienmetral.tasks.task.security.AllowedRolesOrAssignedToOnly;
import io.julienmetral.tasks.task.services.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    // Any authenticated user can create a task; only an admin can assign it on creation
    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or #dto.assignedTo() == null")
    public ResponseEntity<TaskResponseDto> create(
            @Valid @RequestBody CreateTaskDto dto
    ) {
        Task task = taskService.create(dto);

        return ResponseEntity
            .created(URI.create("/api/v1/tasks/" + task.getId()))
            .body(new TaskResponseDto(task));
    }

    @GetMapping
    public Page<TaskResponseDto> findAll(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(defaultValue = "false") boolean archived,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return taskService
                .findAll(status, assigneeId, archived, pageable)
                .map(TaskResponseDto::new);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponseDto> findById(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
            new TaskResponseDto(taskService.findById(id))
        );
    }

    @PatchMapping("/{id}")
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskResponseDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTaskDto dto
    ) {
        return ResponseEntity.ok(
            new TaskResponseDto(taskService.update(id, dto))
        );
    }

    @PatchMapping("/{id}/status")
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskResponseDto> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeTaskStatusDto dto
    ) {
        return ResponseEntity.ok(
            new TaskResponseDto(
                taskService.changeStatus(id, dto.status())
            )
        );
    }

    @PatchMapping("/{id}/assign")
    @AdminOnly
    public ResponseEntity<TaskResponseDto> assign(
            @PathVariable UUID id,
            @Valid @RequestBody AssignTaskDto dto
    ) {
        return ResponseEntity.ok(
            new TaskResponseDto(
                taskService.assign(id, dto.userId())
            )
        );
    }

    @PostMapping("/{id}/archive")
    @AdminOnly
    public ResponseEntity<TaskResponseDto> archive(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
            new TaskResponseDto(taskService.archive(id))
        );
    }

    @PostMapping("/{id}/unarchive")
    @AdminOnly
    public ResponseEntity<TaskResponseDto> unarchive(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
            new TaskResponseDto(taskService.unarchive(id))
        );
    }

    @PostMapping("/{id}/cancel")
    @AllowedRolesOrAssignedToOnly(UserRole.ADMIN)
    public ResponseEntity<TaskResponseDto> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelTaskDto dto
    ) {
        return ResponseEntity.ok(
            new TaskResponseDto(
                taskService.cancel(id, dto.reason())
            )
        );
    }

    @DeleteMapping("/{id}")
    @AdminOnly
    public ResponseEntity<Void> delete(
            @PathVariable UUID id
    ) {
        taskService.delete(id);

        return ResponseEntity.noContent().build();
    }
}