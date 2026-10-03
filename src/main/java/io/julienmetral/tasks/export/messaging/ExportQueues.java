package io.julienmetral.tasks.export.messaging;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Export runs: a message per export, consumed by one listener per instance, which runs the job on its own thread.
 * A failed run is recorded on the export, not retried by the listener: only a message that cannot be read at all goes
 * to {@value #DEAD_LETTER}.
 */
@Configuration
public class ExportQueues {

    public static final String RUN = "export.run";
    public static final String DEAD_LETTER = "export.run.dead-letter";

    static final String LISTENER_FACTORY = "exportListenerContainerFactory";

    @Bean
    Queue exportRunQueue() {
        return QueueBuilder
                .durable(RUN)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER)
                .build();
    }

    @Bean
    Queue exportDeadLetterQueue() {
        return QueueBuilder
                .durable(DEAD_LETTER)
                .build();
    }

    /**
     * Like the default factory, without its retries, one export at a time per instance: an export holds a
     * database cursor and a temporary file, and the message is acknowledged only once its job has ended.
     */
    @Bean(LISTENER_FACTORY)
    SimpleRabbitListenerContainerFactory exportListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain();
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);

        return factory;
    }
}
