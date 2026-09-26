package io.julienmetral.tasks.identity.messaging;

import io.julienmetral.tasks.identity.services.AvatarService;
import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class AvatarProcessingListenerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Mock
    private AvatarService avatarService;

    private AvatarProcessingListener listener;

    @BeforeEach
    void setUp() {
        listener = new AvatarProcessingListener(avatarService);
    }

    @Test
    void processesTheUploadNamedByTheMessage() {
        listener.process(new AvatarUploaded(USER_ID, UPLOAD_ID));

        verify(avatarService).process(USER_ID, UPLOAD_ID);
        verifyNoMoreInteractions(avatarService);
    }

    @Test
    void processingFailurePropagatesSoTheContainerRetries() {
        StorageUnavailableException failure = new StorageUnavailableException(new RuntimeException("down"));
        doThrow(failure).when(avatarService).process(USER_ID, UPLOAD_ID);

        assertThatThrownBy(() -> listener.process(new AvatarUploaded(USER_ID, UPLOAD_ID))).isSameAs(failure);
    }
}
