package io.julienmetral.tasks.export.exceptions;

import java.util.UUID;

public class DataExportNotFoundException extends RuntimeException {

    public DataExportNotFoundException(UUID id) {
        super("Export not found: " + id);
    }
}
