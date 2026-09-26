package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.repositories.TaskAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskAttachmentAuthorizationTest {

    @Mock
    private CurrentUser currentUser;

    @Mock
    private TaskAttachmentRepository attachmentRepository;

    @InjectMocks
    private TaskAttachmentAuthorization authorization;

    private final Authentication authentication = mock(Authentication.class);
    private final UUID attachmentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void trueWhenCurrentUserUploadedTheAttachment() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(userId));
        when(attachmentRepository.existsByIdAndMediaUploadedById(attachmentId, userId)).thenReturn(true);

        assertThat(authorization.currentUserUploaded(attachmentId, authentication)).isTrue();
    }

    @Test
    void falseWhenSomeoneElseUploadedTheAttachment() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(userId));
        when(attachmentRepository.existsByIdAndMediaUploadedById(attachmentId, userId)).thenReturn(false);

        assertThat(authorization.currentUserUploaded(attachmentId, authentication)).isFalse();
    }

    @Test
    void falseWithoutQueryingWhenNoCurrentUser() {
        when(currentUser.getId(authentication)).thenReturn(Optional.empty());

        assertThat(authorization.currentUserUploaded(attachmentId, authentication)).isFalse();
        verifyNoInteractions(attachmentRepository);
    }
}
