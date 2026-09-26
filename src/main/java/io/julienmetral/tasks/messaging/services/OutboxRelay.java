package io.julienmetral.tasks.messaging.services;

import io.julienmetral.tasks.config.OutboxProperties;
import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.AbstractJavaTypeMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(5);

    private static final int MAX_ERROR_LENGTH = 1_000;

    private final OutboxMessageRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;
    private final OutboxProperties properties;
    private final Clock clock;

    /** Publishes these messages as soon as the transaction that wrote them has committed, off the request thread. */
    @Async
    @Transactional
    public void publishNow(Collection<UUID> ids) {
        publish(repository.lockUnpublished(ids));
    }

    /** @return how many messages were published; fewer than the batch size means nothing more is due */
    @Transactional
    public int publishDue() {
        return publish(repository.lockDue(clock.instant(), properties.batchSize()));
    }

    @Transactional
    public int deletePublished() {
        return repository.deletePublishedBefore(clock.instant().minus(properties.retention()));
    }

    private int publish(List<OutboxMessage> messages) {
        int published = 0;

        for (OutboxMessage message : messages) {
            try {
                send(message);
                message.setPublishedAt(clock.instant());
                published++;
            } catch (RuntimeException exception) {
                scheduleRetry(message, exception);
            }
        }

        return published;
    }

    // Waits for the broker's confirm: only a confirmed message is marked published. If the transaction then fails
    // to commit, the message is published again later, hence at least once.
    private void send(OutboxMessage message) {
        MessageProperties messageProperties = new MessageProperties();

        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding("UTF-8");
        messageProperties.setMessageId(message.getId().toString());
        messageProperties.setHeader(AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, message.getType());

        Message amqpMessage = new Message(jsonMapper.writeValueAsBytes(message.getPayload()), messageProperties);

        rabbitTemplate.invoke(operations -> {
            operations.send("", message.getQueue(), amqpMessage);
            operations.waitForConfirmsOrDie(properties.confirmTimeout().toMillis());
            return null;
        });
    }

    private void scheduleRetry(OutboxMessage message, RuntimeException exception) {
        int attempts = message.getAttempts() + 1;
        Duration delay = retryDelay(attempts);
        String error = String.valueOf(exception.getMessage());

        message.setAttempts(attempts);
        message.setNextAttemptAt(clock.instant().plus(delay));
        message.setLastError(error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error);

        log.warn("Could not publish outbox message {} to {} (attempt {}), retrying in {}",
                message.getId(), message.getQueue(), attempts, delay, exception);
    }

    private Duration retryDelay(int attempts) {
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(1L << Math.min(attempts - 1, 20));

        return delay.compareTo(properties.maxRetryDelay()) > 0 ? properties.maxRetryDelay() : delay;
    }
}
