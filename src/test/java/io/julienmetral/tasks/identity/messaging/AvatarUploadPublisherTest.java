package io.julienmetral.tasks.identity.messaging;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AvatarUploadPublisherTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final AvatarUploaded EVENT = new AvatarUploaded(USER_ID, UPLOAD_ID);

    @Mock
    private RabbitTemplate rabbitTemplate;

    private AvatarUploadPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AvatarUploadPublisher(rabbitTemplate);
    }

    @Test
    void publishQueuesTheEventOnTheAvatarProcessQueue() {
        publisher.publish(EVENT);

        verify(rabbitTemplate).convertAndSend("avatar.process", EVENT);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    @Test
    void publishLogsAndSwallowsBrokerFailure(CapturedOutput output) {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatCode(() -> publisher.publish(EVENT)).doesNotThrowAnyException();

        assertThat(output).contains(
                "Could not queue the profile photo " + UPLOAD_ID + " of user " + USER_ID,
                "Connection refused"
        );
    }

    @Test
    void publishDoesNotSwallowOtherFailures() {
        doThrow(new IllegalStateException("boom"))
                .when(rabbitTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatThrownBy(() -> publisher.publish(EVENT)).isInstanceOf(IllegalStateException.class);
    }
}
