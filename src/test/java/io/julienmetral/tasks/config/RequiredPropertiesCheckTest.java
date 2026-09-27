package io.julienmetral.tasks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredPropertiesCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DeploymentConfiguration.class)
            // The machine running the tests may export MAIL_FROM or SPRING_MAIL_HOST
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            });

    private final ApplicationContextRunner requiringMailSettings =
            runner.withPropertyValues("deployment.required-properties=mail.from,spring.mail.host");

    @Test
    void withoutRequiredListTheContextStarts() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void everyRequiredSettingWithAValueLetsTheContextStart() {
        requiringMailSettings
                .withPropertyValues("mail.from=no-reply@example.com", "spring.mail.host=smtp.example.com")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void missingRequiredSettingFailsTheStartupAndNamesIt() {
        requiringMailSettings.withPropertyValues("spring.mail.host=smtp.example.com").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mail.from")
                    .hasMessageNotContaining("spring.mail.host");
        });
    }

    @Test
    void everyMissingRequiredSettingIsNamedInOneMessage() {
        requiringMailSettings.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageStartingWith("Required settings without a value: mail.from, spring.mail.host.");
        });
    }

    @Test
    void blankRequiredSettingFailsTheStartup() {
        requiringMailSettings
                .withPropertyValues("mail.from= ", "spring.mail.host=smtp.example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("mail.from");
                });
    }

    @Test
    void requiredSettingLeftAsAnUnresolvedPlaceholderFailsTheStartup() {
        requiringMailSettings
                .withPropertyValues("mail.from=${MAIL_FROM}", "spring.mail.host=smtp.example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("mail.from");
                });
    }

    @Test
    void requiredSettingWhosePlaceholderHasADefaultValueLetsTheContextStart() {
        requiringMailSettings
                .withPropertyValues("mail.from=${MAIL_FROM:no-reply@example.com}", "spring.mail.host=smtp.example.com")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void missingRequiredSettingFailsTheStartupBeforeAnyOtherBeanIsCreated() {
        AtomicBoolean created = new AtomicBoolean();

        requiringMailSettings
                .withBean("unrelatedBean", Object.class, () -> {
                    created.set(true);
                    return new Object();
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("mail.from");
                });

        assertThat(created).isFalse();
    }
}
