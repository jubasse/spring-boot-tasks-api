package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param enabled scan every upload with ClamAV; when false, uploads are stored unscanned and a warning is logged at
 *                startup (meant for development machines that cannot spare ClamAV's memory)
 * @param host    clamd host
 * @param port    clamd TCP port
 * @param timeout connect and read timeout of one scan
 */
@Validated
@ConfigurationProperties(prefix = "antivirus")
public record AntivirusProperties(
        boolean enabled,
        String host,
        int port,
        @NotNull Duration timeout
) {
}
