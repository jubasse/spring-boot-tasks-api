package io.julienmetral.tasks.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Stops the startup when a property listed in {@code deployment.required-properties} has no value, before any bean is
 * created, so before the database or the broker is reached. The production profile lists the settings it gives no
 * default.
 * <p>
 * Warning: without this check, a missing variable fails nothing. Spring Boot binds an unresolved {@code ${VAR}} as
 * that literal text (spring-boot issue #18816): the application started with a mail host named "${MAIL_HOST}".
 */
class RequiredPropertiesCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String REQUIRED_PROPERTIES = "deployment.required-properties";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> missing = Binder.get(environment)
                .bind(REQUIRED_PROPERTIES, Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .filter(property -> !hasValue(property))
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Required settings without a value: " + String.join(", ", missing)
                    + ". Set them through their environment variables (README, Configuration).");
        }
    }

    private boolean hasValue(String property) {
        try {
            String value = environment.getProperty(property);

            return StringUtils.hasText(value) && !value.contains("${");
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return false;
        }
    }
}
