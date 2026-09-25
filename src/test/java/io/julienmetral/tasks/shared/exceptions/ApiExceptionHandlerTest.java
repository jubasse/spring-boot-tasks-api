package io.julienmetral.tasks.shared.exceptions;

import io.julienmetral.tasks.identity.exceptions.EmailAlreadyVerifiedException;
import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.exceptions.InvalidEmailVerificationTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidPasswordResetTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.exceptions.UserEmailAlreadyExistsException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskNotFoundException;
import io.julienmetral.tasks.task.exceptions.TaskReferenceAlreadyExistsException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void taskNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleTaskNotFound(new TaskNotFoundException(ID));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Task not found");
        assertThat(problem.getDetail()).isEqualTo("Task not found with id: " + ID);
    }

    @Test
    void taskNotFoundByReferenceMapsTo404() {
        ProblemDetail problem = handler.handleTaskNotFound(new TaskNotFoundException("TASK-1"));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isEqualTo("Task not found with reference: TASK-1");
    }

    @Test
    void taskReferenceAlreadyExistsMapsTo409() {
        ProblemDetail problem = handler.handleTaskReferenceAlreadyExists(
                new TaskReferenceAlreadyExistsException("TASK-1"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Task reference already exists");
        assertThat(problem.getDetail()).isEqualTo("Task reference already exists: TASK-1");
    }

    @Test
    void userNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleUserNotFound(new UserNotFoundException(ID));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("User not found");
        assertThat(problem.getDetail()).isEqualTo("User not found with id: " + ID);
    }

    @Test
    void userNotFoundByEmailMapsTo404() {
        ProblemDetail problem = handler.handleUserNotFound(new UserNotFoundException("a@b.c"));

        assertThat(problem.getDetail()).isEqualTo("User not found with email: a@b.c");
    }

    @Test
    void userEmailAlreadyExistsMapsTo409() {
        ProblemDetail problem = handler.handleUserEmailAlreadyExists(
                new UserEmailAlreadyExistsException("a@b.c"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("User email already exists");
        assertThat(problem.getDetail()).isEqualTo("User already exists with email: a@b.c");
    }

    @Test
    void dataIntegrityViolationMapsTo409WithoutLeakingSqlDetails() {
        ProblemDetail problem = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_emailuq\"")
        );

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Data conflict");
        assertThat(problem.getDetail()).doesNotContain("users_emailuq");
    }

    @Test
    void invalidCredentialsMapsTo401WithMessageBody() {
        ResponseEntity<?> response = handler.handleInvalidCredentials(new InvalidCredentialsException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(Map.of(
                "status", 401,
                "message", "Invalid email or password"
        ));
    }

    @Test
    void invalidRefreshTokenMapsTo401() {
        ProblemDetail problem = handler.handleInvalidRefreshToken(new InvalidRefreshTokenException());

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getTitle()).isEqualTo("Invalid refresh token");
        assertThat(problem.getDetail()).isEqualTo("The refresh token is invalid, expired or revoked");
    }

    @Test
    void invalidEmailVerificationTokenMapsTo400() {
        ProblemDetail problem = handler.handleInvalidEmailVerificationToken(
                new InvalidEmailVerificationTokenException());

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Invalid email verification token");
        assertThat(problem.getDetail()).isEqualTo("The email verification token is invalid, expired or already used");
    }

    @Test
    void emailAlreadyVerifiedMapsTo409() {
        ProblemDetail problem = handler.handleEmailAlreadyVerified(new EmailAlreadyVerifiedException());

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Email already verified");
        assertThat(problem.getDetail()).isEqualTo("The email address is already verified");
    }

    @Test
    void invalidPasswordResetTokenMapsTo400() {
        ProblemDetail problem = handler.handleInvalidPasswordResetToken(
                new InvalidPasswordResetTokenException());

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Invalid password reset token");
        assertThat(problem.getDetail()).isEqualTo("The password reset token is invalid, expired or already used");
    }
}
