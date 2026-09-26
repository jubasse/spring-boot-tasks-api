package io.julienmetral.tasks.mail;

import io.julienmetral.tasks.messaging.services.Outbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private Outbox outbox;

    @InjectMocks
    private MailService mailService;

    @Test
    void sendWritesTheMessageToTheOutboxForTheSendQueue() {
        MailMessage message = new MailMessage("jane@example.com", "Subject", "Body");

        mailService.send(message);

        verify(outbox).enqueue(MailQueues.SEND, message);
        verifyNoMoreInteractions(outbox);
    }
}
