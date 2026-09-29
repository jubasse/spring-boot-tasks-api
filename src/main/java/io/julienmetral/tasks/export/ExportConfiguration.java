package io.julienmetral.tasks.export;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ExportProperties.class)
public class ExportConfiguration {
}
