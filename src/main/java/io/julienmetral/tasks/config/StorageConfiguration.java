package io.julienmetral.tasks.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the storage and media properties whatever the driver. Registering them only in the driver configurations
 * meant an unknown {@code storage.driver} loaded no configuration at all, so its validation never ran and startup
 * failed later on a missing bean instead of on the invalid value.
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, MediaProperties.class})
public class StorageConfiguration {
}
