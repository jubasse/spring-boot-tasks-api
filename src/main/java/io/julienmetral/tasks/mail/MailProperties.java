package io.julienmetral.tasks.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param from sender address of every email sent by the application
 */
@Validated
@ConfigurationProperties(prefix = "mail")
public record MailProperties(
        @NotBlank @Email String from
) {
}
