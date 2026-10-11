package io.julienmetral.tasks.task.exceptions;

public class TooManyCommentAttachmentsException extends RuntimeException {

    private final String message;

    public TooManyCommentAttachmentsException(int max) {
        this("A comment can have at most " + max + " files");
    }

    private TooManyCommentAttachmentsException(String message) {
        super(message);
        this.message = message;
    }

    // Never null, unlike Throwable's: it becomes the detail of an invalid value
    @Override
    public String getMessage() {
        return message;
    }
}
