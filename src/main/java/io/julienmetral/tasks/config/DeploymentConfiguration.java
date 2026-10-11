package io.julienmetral.tasks.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DeploymentConfiguration {

    // Static: a BeanFactoryPostProcessor declared by an instance method would force its
    // configuration class to be created before the other post-processors ran
    @Bean
    static RequiredPropertiesCheck requiredPropertiesCheck(Environment environment) {
        return new RequiredPropertiesCheck(environment);
    }
}
