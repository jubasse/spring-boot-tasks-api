package io.julienmetral.tasks.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MailDispatcherTest {

    private static final MailMessage MESSAGE = new MailMessage("jane@example.com", "Subject", "Body");

    private static final MailService.MailRequested EVENT = new MailService.MailRequested(MESSAGE);

    @Mock
    private RabbitTemplate rabbitTemplate;

    private MailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new MailDispatcher(rabbitTemplate);
    }

    @Test
    void dispatchQueuesTheMessageOnTheSendQueue() {
        dispatcher.dispatch(EVENT);

        verify(rabbitTemplate).convertAndSend(MailQueues.SEND, MESSAGE);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    @Test
    void dispatchLogsAndSwallowsBrokerFailure(CapturedOutput output) {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatCode(() -> dispatcher.dispatch(EVENT)).doesNotThrowAnyException();

        assertThat(output).contains("Could not queue email \"Subject\": it will not be sent");
    }

    @Test
    void dispatchDoesNotSwallowOtherFailures() {
        doThrow(new IllegalStateException("boom"))
                .when(rabbitTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatThrownBy(() -> dispatcher.dispatch(EVENT)).isInstanceOf(IllegalStateException.class);
    }
}
