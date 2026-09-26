package io.julienmetral.tasks.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class MessagingConfiguration {

    /**
     * Applied by Spring Boot to RabbitTemplate and to the @RabbitListener containers. The type header of a message
     * may only name a class of the listed packages, besides {@code java.lang} and {@code java.util} which spring-amqp
     * always trusts.
     * <p>
     * Warning: a trusted package matches exactly, not its sub-packages: every package holding a queued message type
     * must be listed.
     */
    @Bean
    MessageConverter messageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(
                jsonMapper,
                "io.julienmetral.tasks.mail"
        );
    }

    /**
     * The listener retry built from {@code spring.rabbitmq.listener.simple.retry} retries every exception, including
     * the {@link AmqpRejectAndDontRequeueException} a listener throws for a message that can never succeed. Without
     * this, such a message was still attempted for about 1.5 minutes before being dead-lettered.
     */
    @Bean
    RabbitListenerRetrySettingsCustomizer noRetryForRejectedMessages() {
        return settings -> settings.setExceptionPredicate(exception -> !isRejected(exception));
    }

    private static boolean isRejected(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
        }

        return false;
    }
}
