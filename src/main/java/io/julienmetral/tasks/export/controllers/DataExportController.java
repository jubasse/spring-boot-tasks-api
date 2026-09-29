package io.julienmetral.tasks.export.controllers;

import io.julienmetral.tasks.export.dtos.DataExportResponseDto;
import io.julienmetral.tasks.export.dtos.ExportTasksDto;
import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.services.DataExportService;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.shared.openapi.DocumentedProblems;
import io.julienmetral.tasks.shared.security.AdminOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Exports", description = "Files of tasks or users, produced in the background and downloadable for a while.")
@RestController
@RequestMapping("/exports")
@RequiredArgsConstructor
public class DataExportController {

    private static final String QUEUED = """
            Queued: follow it at the Location URL. The owner is emailed once the file is ready, or if it could not be \
            produced.""";

    private final DataExportService exportService;
    private final CurrentUser currentUser;
    private final MediaUrls mediaUrls;

    @Operation(
            summary = "Export tasks as CSV",
            description = "Every task matching the filters of the task list, as a CSV file in UTF-8."
    )
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "202", description = QUEUED, headers = @Header(
            name = "Location",
            description = "URL of the export.",
            schema = @Schema(type = "string", format = "uri-reference")
    ), content = @Content(schema = @Schema(implementation = DataExportResponseDto.class)))
    @DocumentedProblems(ProblemType.EXPORT_IN_PROGRESS)
    @PostMapping("/tasks")
    public ResponseEntity<DataExportResponseDto> exportTasks(@Valid @RequestBody(required = false) ExportTasksDto dto) {
        ExportTasksDto filters = dto != null ? dto : new ExportTasksDto(null, null, false);

        return accepted(exportService.request(callerId(), DataExportType.TASKS_CSV, filters.filters()));
    }

    @Operation(
            summary = "Export users as CSV",
            description = "Every account that is not deleted, with its email and roles, as a CSV file in UTF-8."
    )
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "202", description = QUEUED, headers = @Header(
            name = "Location",
            description = "URL of the export.",
            schema = @Schema(type = "string", format = "uri-reference")
    ), content = @Content(schema = @Schema(implementation = DataExportResponseDto.class)))
    @DocumentedProblems(ProblemType.EXPORT_IN_PROGRESS)
    @AdminOnly
    @PostMapping("/users")
    public ResponseEntity<DataExportResponseDto> exportUsers() {
        return accepted(exportService.request(callerId(), DataExportType.USERS_CSV, null));
    }

    @Operation(summary = "List your exports, newest first")
    @GetMapping
    public PagedModel<DataExportResponseDto> listExports(
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return new PagedModel<>(exportService.findOwn(callerId(), pageable).map(this::response));
    }

    @Operation(summary = "Get one of your exports, with a download link once it is completed")
    @GetMapping("/{id}")
    public DataExportResponseDto getExport(@PathVariable UUID id) {
        return response(exportService.getOwn(callerId(), id));
    }

    @Operation(summary = "Delete one of your exports and its file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DocumentedProblems(ProblemType.EXPORT_IN_PROGRESS)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExport(@PathVariable UUID id) {
        exportService.deleteOwn(callerId(), id);

        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<DataExportResponseDto> accepted(DataExport export) {
        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/exports/" + export.getId()))
                .body(response(export));
    }

    private DataExportResponseDto response(DataExport export) {
        return new DataExportResponseDto(export, mediaUrls);
    }

    private UUID callerId() {
        return currentUser.getId().orElseThrow(() -> new AccessDeniedException("No user id in the access token"));
    }
}
