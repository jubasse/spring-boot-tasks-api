package io.julienmetral.tasks.export.events;

import io.julienmetral.tasks.export.entities.DataExportType;

import java.util.UUID;

public record DataExportFailed(UUID exportId, UUID ownerId, DataExportType type) {
}
