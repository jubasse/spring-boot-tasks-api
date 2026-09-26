package io.julienmetral.tasks.messaging;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.mail.MailMessage;
import io.julienmetral.tasks.mail.MailService;
import io.julienmetral.tasks.messaging.entities.OutboxMessage;
import io.julienmetral.tasks.messaging.repositories.OutboxMessageRepository;
import io.julienmetral.tasks.messaging.services.OutboxRelay;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The outbox while RabbitMQ refuses messages: the business transaction still commits, the row keeps the failure,
 * and the relay publishes it once the broker is back. {@link RabbitTemplate} is spied so that publishing fails on
 * demand, and the clock is settable so that retries become due without waiting. The poller runs once at startup,
 * then not before an hour, so each test drives {@link OutboxRelay#publishDue} itself.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class, OutboxBrokerFailureTests.SettableClockConfiguration.class})
@SpringBootTest(properties = "messaging.outbox.poll-interval=PT1H")
class OutboxBrokerFailureTests {

    // Delivery is asynchronous: "sent only once" can only be checked after a grace period
    private static final Duration NO_MAIL_GRACE_PERIOD = Duration.ofMillis(800);

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);

    @TestConfiguration(proxyBeanMethods = false)
    static class SettableClockConfiguration {

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }
    }

    static final class SettableClock extends Clock {

        private volatile Instant instant;

        SettableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    @MockitoSpyBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private SettableClock clock;

    @Autowired
    private MailService mailService;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AtomicBoolean brokerDown = new AtomicBoolean();

    @BeforeEach
    void refusePublishingWhileTheBrokerIsDown() {
        doAnswer(invocation -> {
            if (brokerDown.get()) {
                throw new AmqpConnectException(new ConnectException("Connection refused"));
            }
            return invocation.callRealMethod();
        }).when(rabbitTemplate).invoke(any());
    }

    @Test
    void emailIsKeptWhileTheBrokerIsDownThenRetriedWithBackoffAndDeliveredOnce() throws InterruptedException {
        String email = uniqueEmail();
        Instant sentAt = clock.instant();
        brokerDown.set(true);

        inTransaction().executeWithoutResult(status ->
                mailService.send(new MailMessage(email, "Delayed", "Sent once the broker is back")));

        UUID id = rowIdSentTo(email);
        OutboxMessage failed = awaitAttempts(id, 1);
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getLastError()).isEqualTo("java.net.ConnectException: Connection refused");
        assertThat(failed.getNextAttemptAt()).isEqualTo(sentAt.plusSeconds(5));
        assertThat(mailpit.countTo(email)).isZero();

        relay.publishDue();

        assertThat(row(id).getAttempts()).as("not retried before its next attempt").isEqualTo(1);

        clock.advance(Duration.ofSeconds(5));
        relay.publishDue();

        OutboxMessage retried = row(id);
        assertThat(retried.getPublishedAt()).isNull();
        assertThat(retried.getAttempts()).isEqualTo(2);
        assertThat(retried.getNextAttemptAt()).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(mailpit.countTo(email)).isZero();

        brokerDown.set(false);
        clock.advance(Duration.ofSeconds(10));
        relay.publishDue();

        OutboxMessage published = row(id);
        assertThat(published.getPublishedAt()).isEqualTo(clock.instant());
        assertThat(published.getAttempts()).isEqualTo(2);
        assertThat(mailpit.latestTextTo(email).strip()).isEqualTo("Sent once the broker is back");

        clock.advance(Duration.ofMinutes(20));
        relay.publishDue();
        Thread.sleep(NO_MAIL_GRACE_PERIOD);

        assertThat(mailpit.countTo(email)).isEqualTo(1);
    }

    @Test
    void messageStaysUnpublishedAsLongAsTheBrokerIsDown() {
        String email = uniqueEmail();
        brokerDown.set(true);

        inTransaction().executeWithoutResult(status ->
                mailService.send(new MailMessage(email, "Never confirmed", "Broker down for good")));

        UUID id = rowIdSentTo(email);
        awaitAttempts(id, 1);

        for (int attempt = 2; attempt <= 4; attempt++) {
            clock.advance(Duration.ofMinutes(10));
            relay.publishDue();

            assertThat(row(id).getAttempts()).isEqualTo(attempt);
        }

        assertThat(row(id).getPublishedAt()).isNull();
        assertThat(mailpit.countTo(email)).isZero();

        brokerDown.set(false);
        clock.advance(Duration.ofMinutes(10));
        relay.publishDue();

        assertThat(row(id).getPublishedAt()).isNotNull();
        assertThat(mailpit.latestTextTo(email).strip()).isEqualTo("Broker down for good");
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private UUID rowIdSentTo(String email) {
        List<UUID> ids = jdbcTemplate.queryForList(
                "select id from outbox_messages where payload ->> 'to' = ?",
                UUID.class,
                email
        );

        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private OutboxMessage row(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    // The failed publish is recorded by the async relay, in its own transaction, after the sender's commit
    private OutboxMessage awaitAttempts(UUID id, int attempts) {
        await().atMost(PUBLISH_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> row(id).getAttempts() == attempts);

        return row(id);
    }

    private static String uniqueEmail() {
        return "outbox-broker-" + UUID.randomUUID() + "@example.com";
    }
}
