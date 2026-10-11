package io.julienmetral.tasks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class));

    @Test
    void smtpConnectReadAndWriteAreBoundedByDefault() {
        runner.withInitializer(context -> useWithoutEnvironmentVariables(context.getEnvironment(), mainYaml()))
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context.getBean(JavaMailSenderImpl.class).getJavaMailProperties())
                            .containsEntry("mail.smtp.connectiontimeout", "5000")
                            .containsEntry("mail.smtp.timeout", "15000")
                            .containsEntry("mail.smtp.writetimeout", "15000");
                });
    }

    @Test
    void prodProfileKeepsTheSmtpTimeoutsNextToAuthenticationAndStartTls() {
        runner.withInitializer(context -> useWithoutEnvironmentVariables(context.getEnvironment(), prodYamlOverMain()))
                .withPropertyValues("spring.mail.host=smtp.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);

                    assertThat(sender.getPort()).isEqualTo(587);
                    assertThat(sender.getJavaMailProperties())
                            .containsEntry("mail.smtp.connectiontimeout", "5000")
                            .containsEntry("mail.smtp.timeout", "15000")
                            .containsEntry("mail.smtp.writetimeout", "15000")
                            .containsEntry("mail.smtp.auth", "true")
                            .containsEntry("mail.smtp.starttls.enable", "true")
                            .containsEntry("mail.smtp.starttls.required", "true");
                });
    }

    // The machine running the tests may export MAIL_HOST, MAIL_PORT or MAIL_STARTTLS
    private static void useWithoutEnvironmentVariables(ConfigurableEnvironment environment,
                                                       List<PropertySource<?>> yaml) {
        MutablePropertySources sources = environment.getPropertySources();

        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        yaml.forEach(sources::addLast);
    }

    private static List<PropertySource<?>> mainYaml() {
        return List.of(yaml("main application.yaml", "application.yaml"));
    }

    // The profile above the main application.yaml, as Spring Boot orders them
    private static List<PropertySource<?>> prodYamlOverMain() {
        return List.of(
                yaml("prod application.yaml", "application-prod.yaml"),
                yaml("main application.yaml", "application.yaml"));
    }

    private static PropertySource<?> yaml(String name, String path) {
        try {
            return new YamlPropertySourceLoader().load(name, new ClassPathResource(path)).getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
