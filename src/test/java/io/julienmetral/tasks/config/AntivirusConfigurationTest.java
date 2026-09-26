package io.julienmetral.tasks.config;

import io.julienmetral.tasks.media.services.ClamAvScanner;
import io.julienmetral.tasks.media.services.VirusScanner;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AntivirusConfigurationTest {

    private static final String DISABLED_WARNING =
            "Antivirus scanning is disabled (antivirus.enabled=false): uploads are stored unscanned";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AntivirusConfiguration.class)
            .withPropertyValues("antivirus.timeout=60s");

    @Nested
    class Enabled {

        private final ApplicationContextRunner enabled = runner.withPropertyValues(
                "antivirus.enabled=true",
                "antivirus.host=clamav",
                "antivirus.port=3311"
        );

        @Test
        void buildsAClamAvScannerForTheConfiguredServer(CapturedOutput output) {
            enabled.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(VirusScanner.class);
                assertThat(context.getBean(VirusScanner.class))
                        .isInstanceOf(ClamAvScanner.class)
                        .extracting("host", "port", "timeoutMillis")
                        .containsExactly("clamav", 3311, 60_000);
            });

            assertThat(output).doesNotContain(DISABLED_WARNING);
        }

        @Test
        void bindsTheProperties() {
            enabled.withPropertyValues("antivirus.timeout=2s").run(context -> {
                AntivirusProperties properties = context.getBean(AntivirusProperties.class);
                assertThat(properties.enabled()).isTrue();
                assertThat(properties.host()).isEqualTo("clamav");
                assertThat(properties.port()).isEqualTo(3311);
                assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(2));
            });
        }

        @Test
        void missingHostFailsStartup() {
            runner.withPropertyValues("antivirus.enabled=true").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("antivirus.host must be set when antivirus.enabled is true"));
        }

        @Test
        void blankHostFailsStartup() {
            runner.withPropertyValues("antivirus.enabled=true", "antivirus.host= ").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("antivirus.host must be set when antivirus.enabled is true"));
        }

        @Test
        void missingTimeoutFailsStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(AntivirusConfiguration.class)
                    .withPropertyValues("antivirus.enabled=true", "antivirus.host=clamav", "antivirus.port=3310")
                    .run(context -> assertThat(context).getFailure()
                            .hasStackTraceContaining("antivirus.timeout"));
        }
    }

    @Nested
    class Disabled {

        @Test
        void acceptsEverythingWithoutReadingAndWarnsAtStartup(CapturedOutput output) {
            runner.withPropertyValues("antivirus.enabled=false").run(context -> {
                assertThat(context).hasNotFailed();
                VirusScanner scanner = context.getBean(VirusScanner.class);
                assertThat(scanner).isNotInstanceOf(ClamAvScanner.class);

                InputStream eicar = new ByteArrayInputStream(
                        "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                                .getBytes(StandardCharsets.US_ASCII));
                assertThat(scanner.findThreat(eicar)).isEmpty();
                assertThat(eicar.available()).isEqualTo(68);
            });

            assertThat(output).contains(DISABLED_WARNING);
        }

        @Test
        void needsNoHost() {
            runner.withPropertyValues("antivirus.enabled=false").run(context -> assertThat(context).hasNotFailed().hasSingleBean(VirusScanner.class));
        }
    }
}
