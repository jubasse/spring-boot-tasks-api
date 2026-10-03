package io.julienmetral.tasks.realtime;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Base64UrlNamingStrategy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import java.util.Map;

/**
 * Real-time events reach every instance: they are broadcast through the outbox to the fanout exchange
 * {@value #EXCHANGE}, which copies each one to a queue of every running instance. That queue is the instance's own,
 * deleted with its connection, so an instance only receives the events published while it listens.
 */
@Configuration
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfiguration {

    public static final String EXCHANGE = "tasks.realtime";

    public static final String QUEUE_BEAN = "realtimeQueue";

    public static final String LISTENER_FACTORY = "realtimeListenerContainerFactory";

    @Bean
    FanoutExchange realtimeExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    // Exclusive and auto-delete, with a name only this instance knows; RabbitAdmin declares it again after a
    // reconnection
    @Bean(QUEUE_BEAN)
    AnonymousQueue realtimeQueue(RealtimeProperties properties) {
        return new AnonymousQueue(
                new Base64UrlNamingStrategy(EXCHANGE + "."),
                Map.of("x-max-length", properties.queueMaxLength())
        );
    }

    @Bean
    Binding realtimeBinding(FanoutExchange realtimeExchange, AnonymousQueue realtimeQueue) {
        return BindingBuilder.bind(realtimeQueue).to(realtimeExchange);
    }

    /**
     * One consumer, so the events of this instance are handed out in the order they arrive, and no retries: an event
     * that fails is dropped, since a stale real-time event is worth less than the ones behind it.
     */
    @Bean(LISTENER_FACTORY)
    SimpleRabbitListenerContainerFactory realtimeListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain();
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);

        return factory;
    }

    // A stream lasts up to 15 minutes: recorded in http.server.requests, it would swamp the latency of every other
    // request
    @Bean
    ObservationPredicate noObservationOfNotificationStreams() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().endsWith("/notifications/stream"));
    }
}
