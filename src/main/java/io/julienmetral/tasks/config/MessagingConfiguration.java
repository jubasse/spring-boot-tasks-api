package io.julienmetral.tasks.config;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class MessagingConfiguration {

    // Spring Boot applies this converter to RabbitTemplate and to the @RabbitListener containers. Only the
    // application's own types may be deserialized from the type header of a message.
    @Bean
    MessageConverter messageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper, "io.julienmetral.tasks");
    }
}
