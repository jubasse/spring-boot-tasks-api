package io.julienmetral.tasks.notification.webhook;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deliveries to attempt wait in {@value #DELIVER} for {@link WebhookDeliveryListener}. Their retries are scheduled in
 * the database, not in the listener: only a message that cannot be processed at all goes to {@value #DEAD_LETTER}.
 */
@Configuration
public class WebhookQueues {

    public static final String DELIVER = "webhook.deliver";
    public static final String DEAD_LETTER = "webhook.deliver.dead-letter";

    static final String LISTENER_FACTORY = "webhookListenerContainerFactory";

    @Bean
    Queue webhookDeliverQueue() {
        return QueueBuilder
                .durable(DELIVER)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER)
                .build();
    }

    @Bean
    Queue webhookDeadLetterQueue() {
        return QueueBuilder
                .durable(DEAD_LETTER)
                .build();
    }

    /**
     * Like the default factory, without its retries: they sleep on the consumer thread, and a receiver that times out
     * would hold a consumer for minutes. One message at a time per consumer, so a slow receiver delays only its own.
     */
    @Bean(LISTENER_FACTORY)
    SimpleRabbitListenerContainerFactory webhookListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            WebhookProperties properties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain();
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(properties.concurrentDeliveries());
        factory.setMaxConcurrentConsumers(properties.concurrentDeliveries());

        return factory;
    }
}
