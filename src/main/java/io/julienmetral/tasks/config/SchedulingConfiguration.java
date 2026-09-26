package io.julienmetral.tasks.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        MediaCleanupProperties.class,
        TaskReminderProperties.class,
        UserRetentionProperties.class
})
public class SchedulingConfiguration {
}
