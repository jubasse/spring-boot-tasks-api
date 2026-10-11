package io.julienmetral.tasks.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Not in SchedulingConfiguration: every test that loads it would then have to supply these properties too
@Configuration
@EnableConfigurationProperties(ExpiredTokenPurgeProperties.class)
public class ExpiredTokenPurgeConfiguration {
}
