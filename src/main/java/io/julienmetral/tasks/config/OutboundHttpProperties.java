package io.julienmetral.tasks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param allowedAddresses address ranges in CIDR notation that outgoing HTTP calls may reach besides public addresses,
 *                         for example {@code 127.0.0.1/32} for a receiver on the developer's machine. Empty in
 *                         production: a range listed here is reachable through any user-supplied URL.
 */
@ConfigurationProperties(prefix = "outbound-http")
public record OutboundHttpProperties(
        @DefaultValue List<String> allowedAddresses
) {
}
