package io.julienmetral.tasks.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MailQueueListener {

    private final JavaMailSender mailSender;
    private final MailProperties properties;

    /**
     * Sends one queued email. An SMTP failure is thrown, so the listener retries with backoff
     * ({@code spring.rabbitmq.listener.simple.retry}) and then dead-letters the message. A message that can never be
     * built is dead-lettered at once.
     * <p>
     * Delivery is at least once: a crash between the SMTP send and the acknowledgement sends the email twice.
     */
    @RabbitListener(queues = MailQueues.SEND)
    void send(MailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();

        mail.setFrom(properties.from());
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());

        try {
            mailSender.send(mail);
        } catch (MailParseException | MailPreparationException exception) {
            throw new AmqpRejectAndDontRequeueException("Invalid email \"" + message.subject() + "\"", exception);
        }
    }
}
