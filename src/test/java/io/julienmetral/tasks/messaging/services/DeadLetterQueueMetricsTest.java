package io.julienmetral.tasks.messaging.services;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterQueueMetricsTest {

    private static final String MAIL = "mail.send.dead-letter";

    private static final String AVATAR = "avatar.process.dead-letter";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Mock
    private AmqpAdmin amqpAdmin;

    @Test
    void registersOneGaugeTaggedWithEachQueue() {
        bind(List.of(MAIL, AVATAR));

        assertThat(registry.get("rabbitmq.dead.letter.messages").gauges())
                .extracting(gauge -> gauge.getId().getTag("queue"))
                .containsExactlyInAnyOrder(MAIL, AVATAR);
        assertThat(registry.get("rabbitmq.dead.letter.messages").gauges())
                .allSatisfy(gauge -> assertThat(gauge.getId().getTags()).hasSize(1));
    }

    @Test
    void bindingDoesNotAskTheBroker() {
        bind(List.of(MAIL, AVATAR));

        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void gaugeIsTheMessageCountOfItsOwnQueue() {
        bind(List.of(MAIL, AVATAR));
        when(amqpAdmin.getQueueInfo(MAIL)).thenReturn(new QueueInformation(MAIL, 3, 0));
        when(amqpAdmin.getQueueInfo(AVATAR)).thenReturn(new QueueInformation(AVATAR, 0, 0));

        assertThat(gauge(MAIL).value()).isEqualTo(3.0);
        assertThat(gauge(AVATAR).value()).isZero();
    }

    @Test
    void gaugeAsksTheBrokerAgainOnEveryScrape() {
        bind(List.of(MAIL));
        when(amqpAdmin.getQueueInfo(MAIL))
                .thenReturn(new QueueInformation(MAIL, 1, 0), new QueueInformation(MAIL, 4, 0));

        assertThat(gauge(MAIL).value()).isEqualTo(1.0);
        assertThat(gauge(MAIL).value()).isEqualTo(4.0);
    }

    @Test
    void missingQueueIsNaN() {
        bind(List.of(MAIL));
        when(amqpAdmin.getQueueInfo(MAIL)).thenReturn(null);

        assertThat(gauge(MAIL).value()).isNaN();
    }

    @Test
    void unreachableBrokerIsNaN() {
        bind(List.of(MAIL));
        when(amqpAdmin.getQueueInfo(MAIL)).thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        assertThat(gauge(MAIL).value()).isNaN();
    }

    @Test
    void brokerFailureOnOneQueueLeavesTheOthersMeasured() {
        bind(List.of(MAIL, AVATAR));
        when(amqpAdmin.getQueueInfo(MAIL)).thenThrow(new AmqpIOException(new IOException("channel closed")));
        when(amqpAdmin.getQueueInfo(AVATAR)).thenReturn(new QueueInformation(AVATAR, 2, 0));

        assertThat(gauge(MAIL).value()).isNaN();
        assertThat(gauge(AVATAR).value()).isEqualTo(2.0);
    }

    @Test
    void queuesAddedToTheListAfterConstructionAreNotMeasured() {
        List<String> queues = new ArrayList<>(List.of(MAIL));
        DeadLetterQueueMetrics metrics = new DeadLetterQueueMetrics(amqpAdmin, queues);
        queues.add(AVATAR);

        metrics.bindTo(registry);

        assertThat(registry.get("rabbitmq.dead.letter.messages").gauges()).singleElement()
                .satisfies(gauge -> assertThat(gauge.getId().getTags()).containsExactly(Tag.of("queue", MAIL)));
    }

    private void bind(List<String> queues) {
        new DeadLetterQueueMetrics(amqpAdmin, queues).bindTo(registry);
    }

    private Gauge gauge(String queue) {
        return registry.get("rabbitmq.dead.letter.messages").tag("queue", queue).gauge();
    }
}
