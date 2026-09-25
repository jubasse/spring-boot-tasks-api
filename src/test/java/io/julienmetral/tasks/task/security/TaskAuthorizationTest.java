package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.task.repositories.TaskRepository;
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
class TaskAuthorizationTest {

    @Mock
    private CurrentUser currentUser;

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private TaskAuthorization taskAuthorization;

    private final Authentication authentication = mock(Authentication.class);
    private final UUID taskId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void trueWhenTaskAssignedToCurrentUser() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(userId));
        when(taskRepository.existsByIdAndAssignedToId(taskId, userId)).thenReturn(true);

        assertThat(taskAuthorization.currentUserIsAssignedTo(taskId, authentication)).isTrue();
    }

    @Test
    void falseWhenTaskNotAssignedToCurrentUser() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(userId));
        when(taskRepository.existsByIdAndAssignedToId(taskId, userId)).thenReturn(false);

        assertThat(taskAuthorization.currentUserIsAssignedTo(taskId, authentication)).isFalse();
    }

    @Test
    void falseWithoutQueryingWhenNoCurrentUser() {
        when(currentUser.getId(authentication)).thenReturn(Optional.empty());

        assertThat(taskAuthorization.currentUserIsAssignedTo(taskId, authentication)).isFalse();
        verifyNoInteractions(taskRepository);
    }
}
