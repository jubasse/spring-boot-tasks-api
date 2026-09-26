package io.julienmetral.tasks.task.exceptions;

public class TooManyCommentAttachmentsException extends RuntimeException {

    public TooManyCommentAttachmentsException(int max) {
        super("A comment can have at most " + max + " files");
    }
}
