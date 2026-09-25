package io.julienmetral.tasks.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MailService mailService;

    @Test
    void sendPublishesEventHandledAfterCommit() {
        MailMessage message = new MailMessage("jane@example.com", "Subject", "Body");

        mailService.send(message);

        verify(eventPublisher).publishEvent(new MailService.MailRequested(message));
    }
}
