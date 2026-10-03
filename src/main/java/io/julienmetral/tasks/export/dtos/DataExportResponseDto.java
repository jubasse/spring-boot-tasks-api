package io.julienmetral.tasks.export.dtos;

import io.julienmetral.tasks.export.entities.DataExport;
import io.julienmetral.tasks.export.entities.DataExportStatus;
import io.julienmetral.tasks.export.entities.DataExportType;
import io.julienmetral.tasks.export.entities.TaskExportFilters;
import io.julienmetral.tasks.media.services.MediaUrls;

import java.time.Instant;
import java.util.UUID;

/**
 * @param filters     the filters of a tasks export, null for the other types
 * @param rowCount    rows written, once completed
 * @param downloadUrl a link to the file, valid a few minutes, while the export is completed; ask again for a new one
 */
public record DataExportResponseDto(
        UUID id,
        DataExportType type,
        DataExportStatus status,
        TaskExportFilters filters,
        Long rowCount,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt,
        String downloadUrl
) {

    public DataExportResponseDto(DataExport export, MediaUrls mediaUrls) {
        this(
                export.getId(),
                export.getType(),
                export.getStatus(),
                export.getTaskFilters(),
                export.getRowCount(),
                export.getCreatedAt(),
                export.getCompletedAt(),
                export.getExpiresAt(),
                export.getStatus() == DataExportStatus.COMPLETED && export.getMedia() != null
                        ? mediaUrls.of(export.getMedia())
                        : null
        );
    }
}
