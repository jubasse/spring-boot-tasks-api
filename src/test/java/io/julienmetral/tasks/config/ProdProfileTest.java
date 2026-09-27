package io.julienmetral.tasks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProdProfileTest {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "security.jwt.secret",
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.rabbitmq.host",
            "spring.mail.host",
            "mail.from",
            "identity.email-verification.verify-url",
            "identity.password-reset.reset-url",
            "storage.bucket",
            "antivirus.host"
    );

    @Test
    void prodYamlActivatesNoProfile() {
        assertThat(prodYaml()).isNotEmpty().allSatisfy(document -> {
            assertThat(document.containsProperty("spring.profiles.active")).isFalse();
            assertThat(document.containsProperty("spring.profiles.include")).isFalse();
        });
    }

    @Test
    void prodYamlRequiresEverySettingItGivesNoDefault() {
        MutablePropertySources sources = new MutablePropertySources();
        prodYaml().forEach(sources::addLast);

        List<String> required = new Binder(ConfigurationPropertySources.from(sources))
                .bind(RequiredPropertiesCheck.REQUIRED_PROPERTIES, Bindable.listOf(String.class))
                .orElse(List.of());

        assertThat(required).containsExactlyInAnyOrderElementsOf(REQUIRED_PROPERTIES);
    }

    @Test
    void prodProfileWithoutEnvironmentVariablesFailsTheStartupNamingEveryRequiredSetting() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeploymentConfiguration.class)
                .withInitializer(context -> useProdProfileWithoutEnvironmentVariables(context.getEnvironment()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContainingAll(REQUIRED_PROPERTIES.toArray(String[]::new));
                });
    }

    @Test
    void prodProfileTurnsOffTheApiDocumentationByDefault() {
        ConfigurableEnvironment environment = prodEnvironmentWithoutEnvironmentVariables();

        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
    }

    @Test
    void prodProfileStoresMediaOnAwsS3ByDefault() {
        assertThat(prodEnvironmentWithoutEnvironmentVariables().getProperty("storage.driver")).isEqualTo("aws-s3");
    }

    @Test
    void prodProfileWritesEcsStructuredConsoleLogs() {
        assertThat(prodEnvironmentWithoutEnvironmentVariables().getProperty("logging.structured.format.console"))
                .isEqualTo("ecs");
    }

    private static ConfigurableEnvironment prodEnvironmentWithoutEnvironmentVariables() {
        StandardEnvironment environment = new StandardEnvironment();
        useProdProfileWithoutEnvironmentVariables(environment);

        return environment;
    }

    // The main application.yaml sits below the profile, as Spring Boot orders them, so the profile must override its
    // development defaults. The machine running the tests may export MAIL_FROM or S3_BUCKET.
    private static void useProdProfileWithoutEnvironmentVariables(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();

        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        prodYaml().forEach(sources::addLast);
        sources.addLast(yaml("main application.yaml", "application.yaml").getFirst());
    }

    private static List<PropertySource<?>> prodYaml() {
        return yaml("prod application.yaml", "application-prod.yaml");
    }

    private static List<PropertySource<?>> yaml(String name, String path) {
        try {
            return new YamlPropertySourceLoader().load(name, new ClassPathResource(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
