package io.julienmetral.tasks.config;

import io.julienmetral.tasks.media.services.ClamAvScanner;
import io.julienmetral.tasks.media.services.VirusScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.util.Optional;

@Slf4j
@Configuration
@EnableConfigurationProperties(AntivirusProperties.class)
public class AntivirusConfiguration {

    @Bean
    VirusScanner virusScanner(AntivirusProperties properties) {
        if (!properties.enabled()) {
            log.warn("Antivirus scanning is disabled (antivirus.enabled=false): uploads are stored unscanned");

            return content -> Optional.empty();
        }

        Assert.hasText(properties.host(), "antivirus.host must be set when antivirus.enabled is true");

        return new ClamAvScanner(properties.host(), properties.port(), properties.timeout());
    }
}
