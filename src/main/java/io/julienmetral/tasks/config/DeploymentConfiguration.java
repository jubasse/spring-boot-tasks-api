package io.julienmetral.tasks.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DeploymentConfiguration {

    // Static: a BeanFactoryPostProcessor declared by an instance method would force its configuration class to be
    // created before the other post-processors ran
    @Bean
    static RequiredPropertiesCheck requiredPropertiesCheck() {
        return new RequiredPropertiesCheck();
    }
}
