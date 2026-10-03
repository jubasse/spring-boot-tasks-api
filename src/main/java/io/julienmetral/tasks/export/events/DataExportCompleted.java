package io.julienmetral.tasks.export.events;

import io.julienmetral.tasks.export.entities.DataExportType;

import java.time.Instant;
import java.util.UUID;

public record DataExportCompleted(UUID exportId, UUID ownerId, DataExportType type, Instant expiresAt) {
}
