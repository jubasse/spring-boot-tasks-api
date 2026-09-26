package io.julienmetral.tasks.mail;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Emails to send wait in {@value #SEND}. A message whose delivery still fails after the listener's retries is moved
 * to {@value #DEAD_LETTER}, where it stays for inspection or a manual replay from the management UI.
 */
@Configuration
public class MailQueues {

    public static final String SEND = "mail.send";

    public static final String DEAD_LETTER = "mail.send.dead-letter";

    @Bean
    Queue mailSendQueue() {
        return QueueBuilder
                .durable(SEND)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER)
                .build();
    }

    @Bean
    Queue mailDeadLetterQueue() {
        return QueueBuilder
                .durable(DEAD_LETTER)
                .build();
    }
}
