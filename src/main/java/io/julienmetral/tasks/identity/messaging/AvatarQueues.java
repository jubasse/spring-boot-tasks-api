package io.julienmetral.tasks.identity.messaging;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Uploaded profile photos wait in {@value #PROCESS} for {@link AvatarProcessingListener}. A message that still fails
 * after the listener's retries (storage down, for example) goes to {@value #DEAD_LETTER}; the user's photo then stays
 * pending until they upload again.
 */
@Configuration
public class AvatarQueues {

    public static final String PROCESS = "avatar.process";

    public static final String DEAD_LETTER = "avatar.process.dead-letter";

    @Bean
    Queue avatarProcessQueue() {
        return QueueBuilder
                .durable(PROCESS)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER)
                .build();
    }

    @Bean
    Queue avatarDeadLetterQueue() {
        return QueueBuilder
                .durable(DEAD_LETTER)
                .build();
    }
}
