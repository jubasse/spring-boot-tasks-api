package io.julienmetral.tasks.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

// With spring.threads.virtual.enabled, @Async methods run on virtual threads
@Configuration
@EnableAsync
@EnableConfigurationProperties(MailProperties.class)
public class MailConfiguration {
}
