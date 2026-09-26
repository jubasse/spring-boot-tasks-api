package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.repositories.TaskCommentRepository;
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
class TaskCommentAuthorizationTest {

    @Mock
    private CurrentUser currentUser;

    @Mock
    private TaskCommentRepository commentRepository;

    @InjectMocks
    private TaskCommentAuthorization authorization;

    private final Authentication authentication = mock(Authentication.class);
    private final UUID commentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void trueWhenCurrentUserWroteTheComment() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(userId));
        when(commentRepository.existsByIdAndAuthorId(commentId, userId)).thenReturn(true);

        assertThat(authorization.currentUserWrote(commentId, authentication)).isTrue();
    }

    @Test
    void falseWhenSomeoneElseWroteTheComment() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(userId));
        when(commentRepository.existsByIdAndAuthorId(commentId, userId)).thenReturn(false);

        assertThat(authorization.currentUserWrote(commentId, authentication)).isFalse();
    }

    @Test
    void falseWithoutQueryingWhenNoCurrentUser() {
        when(currentUser.getId(authentication)).thenReturn(Optional.empty());

        assertThat(authorization.currentUserWrote(commentId, authentication)).isFalse();
        verifyNoInteractions(commentRepository);
    }
}
