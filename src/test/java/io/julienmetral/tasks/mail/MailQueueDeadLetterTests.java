package io.julienmetral.tasks.mail;

import io.julienmetral.tasks.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Retries and dead-lettering of the mail queue, against a real broker with SMTP replaced by a mock. The retry
 * backoff is shortened so that a message reaches the dead-letter queue in well under a second, and the mail health
 * indicator is off because it needs a real {@code JavaMailSenderImpl}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.retry.max-retries=" + MailQueueDeadLetterTests.MAX_RETRIES,
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=100ms",
        "management.health.mail.enabled=false"
})
class MailQueueDeadLetterTests {

    static final int MAX_RETRIES = 2;

    private static final long DEAD_LETTER_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private MailService mailService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JsonMapper jsonMapper;

    @AfterEach
    void purgeDeadLetterQueue() {
        amqpAdmin.purgeQueue(MailQueues.DEAD_LETTER, false);
    }

    @Test
    void smtpFailureIsRetriedThenDeadLettered() {
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));
        MailMessage sent = new MailMessage(uniqueEmail(), "Undeliverable", "SMTP keeps failing");

        mailService.send(sent);

        Message deadLetter = rabbitTemplate.receive(MailQueues.DEAD_LETTER, DEAD_LETTER_TIMEOUT_MILLIS);
        assertThat(deadLetter).isNotNull();
        assertThat(jsonMapper.readValue(deadLetter.getBody(), MailMessage.class)).isEqualTo(sent);
        assertRejectedFromSendQueue(deadLetter);
        verify(mailSender, times(1 + MAX_RETRIES)).send(any(SimpleMailMessage.class));
    }

    @Test
    void deadLetteredMessageIsReadBackWithReceiveAndConvert() {
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));
        MailMessage sent = new MailMessage(uniqueEmail(), "Undeliverable", "SMTP keeps failing");

        mailService.send(sent);

        // Without an explicit type, the converter refuses MailMessage (see MessagingConfigurationTest)
        MailMessage deadLetter = rabbitTemplate.receiveAndConvert(
                MailQueues.DEAD_LETTER,
                DEAD_LETTER_TIMEOUT_MILLIS,
                new ParameterizedTypeReference<MailMessage>() {
                }
        );
        assertThat(deadLetter).isEqualTo(sent);
    }

    @Test
    void transientSmtpFailureIsRetriedAndNotDeadLettered() {
        doThrow(new MailSendException("SMTP down"))
                .doNothing()
                .when(mailSender).send(any(SimpleMailMessage.class));

        mailService.send(new MailMessage(uniqueEmail(), "Delivered on retry", "SMTP recovers"));

        verify(mailSender, timeout(DEAD_LETTER_TIMEOUT_MILLIS).times(2)).send(any(SimpleMailMessage.class));
        assertThat(rabbitTemplate.receive(MailQueues.DEAD_LETTER, 500)).isNull();
        assertThat(sendQueue().getMessageCount()).isZero();
    }

    @Test
    void unparsableEmailIsDeadLettered() {
        doThrow(new MailParseException("Invalid address")).when(mailSender).send(any(SimpleMailMessage.class));
        MailMessage sent = new MailMessage(uniqueEmail(), "Unparsable", "Never valid");

        mailService.send(sent);

        Message deadLetter = rabbitTemplate.receive(MailQueues.DEAD_LETTER, DEAD_LETTER_TIMEOUT_MILLIS);
        assertThat(deadLetter).isNotNull();
        assertThat(jsonMapper.readValue(deadLetter.getBody(), MailMessage.class)).isEqualTo(sent);
        assertRejectedFromSendQueue(deadLetter);
    }

    @Test
    void unparsableEmailIsDeadLetteredAfterASingleAttempt() {
        doThrow(new MailParseException("Invalid address")).when(mailSender).send(any(SimpleMailMessage.class));

        mailService.send(new MailMessage(uniqueEmail(), "Unparsable", "Never valid"));

        assertThat(rabbitTemplate.receive(MailQueues.DEAD_LETTER, DEAD_LETTER_TIMEOUT_MILLIS)).isNotNull();
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    private static void assertRejectedFromSendQueue(Message deadLetter) {
        List<Map<String, ?>> deaths = deadLetter.getMessageProperties().getXDeathHeader();

        assertThat(deaths).singleElement().satisfies(death -> {
            assertThat(death.get("queue")).isEqualTo(MailQueues.SEND);
            assertThat(death.get("reason")).isEqualTo("rejected");
            assertThat(death.get("count")).isEqualTo(1L);
        });
    }

    private QueueInformation sendQueue() {
        return amqpAdmin.getQueueInfo(MailQueues.SEND);
    }

    private static String uniqueEmail() {
        return "dead-letter-" + UUID.randomUUID() + "@example.com";
    }
}
