package io.julienmetral.tasks.export.exceptions;

public class DataExportInProgressException extends RuntimeException {

    public DataExportInProgressException() {
        super("An export of the same kind is already queued or running: wait for it to finish");
    }
}
