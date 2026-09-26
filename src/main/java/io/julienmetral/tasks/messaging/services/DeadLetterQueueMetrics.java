package io.julienmetral.tasks.messaging.services;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.util.List;

/**
 * Messages waiting in each dead-letter queue, asked to RabbitMQ on every scrape: anything above 0 is a message that
 * failed all its retries and needs a look. Reported as NaN while the broker cannot be asked.
 */
public class DeadLetterQueueMetrics implements MeterBinder {

    private final AmqpAdmin amqpAdmin;
    private final List<String> queues;

    public DeadLetterQueueMetrics(AmqpAdmin amqpAdmin, List<String> queues) {
        this.amqpAdmin = amqpAdmin;
        this.queues = List.copyOf(queues);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String queue : queues) {
            Gauge.builder("rabbitmq.dead.letter.messages", () -> messageCount(queue))
                    .description("Messages in a dead-letter queue")
                    .tag("queue", queue)
                    .register(registry);
        }
    }

    private double messageCount(String queue) {
        try {
            QueueInformation information = amqpAdmin.getQueueInfo(queue);

            return information == null ? Double.NaN : information.getMessageCount();
        } catch (AmqpException brokerUnreachable) {
            return Double.NaN;
        }
    }
}
