package io.julienmetral.tasks.shared.exceptions;

import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.exceptions.UserEmailAlreadyExistsException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail handleTaskNotFound(TaskNotFoundException ex) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problem.setTitle("Task not found");

        return problem;
    }

    @ExceptionHandler(TaskReferenceAlreadyExistsException.class)
    public ProblemDetail handleTaskReferenceAlreadyExists(
        TaskReferenceAlreadyExistsException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problem.setTitle("Task reference already exists");

        return problem;
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problem.setTitle("User not found");

        return problem;
    }

    @ExceptionHandler(UserEmailAlreadyExistsException.class)
    public ProblemDetail handleUserEmailAlreadyExists(
        UserEmailAlreadyExistsException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problem.setTitle("User email already exists");

        return problem;
    }

    // Safety net for races between an existence check and the insert (e.g. two concurrent sign-ups with one email).
    // The detail stays generic so no SQL or constraint name leaks to the client.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(
        DataIntegrityViolationException ex
    ) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "The request conflicts with existing data"
        );

        problem.setTitle("Data conflict");

        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<?> handleInvalidCredentials(
            InvalidCredentialsException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(
                        Map.of(
                                "status", 401,
                                "message", exception.getMessage()
                        )
                );
    }
}