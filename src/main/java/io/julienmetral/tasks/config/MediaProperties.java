package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * @param avatarMaxSize     largest accepted profile photo upload, before resizing
 * @param attachmentMaxSize largest accepted task attachment; {@code spring.servlet.multipart.max-file-size} must be
 *                          at least as large, or the request is rejected before it reaches the application
 */
@Validated
@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        @NotNull DataSize avatarMaxSize,
        @NotNull DataSize attachmentMaxSize
) {
}
