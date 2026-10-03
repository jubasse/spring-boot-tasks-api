package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.identity.security.UserStatusLookup;
import io.julienmetral.tasks.task.repositories.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRoomAuthorizationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private UserStatusLookup userStatusLookup;

    @Mock
    private TaskRepository taskRepository;

    private TaskRoomAuthorization authorization;

    @BeforeEach
    void createAuthorization() {
        authorization = new TaskRoomAuthorization(new CurrentUser(), userStatusLookup, taskRepository);
    }

    @Test
    void activeAccountMaySubscribeToTheRoomOfAnExistingTask() {
        when(userStatusLookup.statusOf(USER_ID)).thenReturn(UserStatus.ACTIVE);
        when(taskRepository.existsById(TASK_ID)).thenReturn(true);

        assertThat(isGranted(user(USER_ID), TASK_ID.toString())).isTrue();
    }

    @Test
    void roomOfATaskThatDoesNotExistIsRefused() {
        when(userStatusLookup.statusOf(USER_ID)).thenReturn(UserStatus.ACTIVE);
        when(taskRepository.existsById(TASK_ID)).thenReturn(false);

        assertThat(isGranted(user(USER_ID), TASK_ID.toString())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void accountThatIsNoLongerActiveIsRefusedWithoutLookingForTheTask(UserStatus status) {
        when(userStatusLookup.statusOf(USER_ID)).thenReturn(status);

        assertThat(isGranted(user(USER_ID), TASK_ID.toString())).isFalse();
        verifyNoInteractions(taskRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "", "42"})
    void roomWhoseIdIsNotATaskIdIsRefusedWithoutAnyLookup(String taskId) {
        assertThat(isGranted(user(USER_ID), taskId)).isFalse();
        verifyNoInteractions(userStatusLookup, taskRepository);
    }

    @Test
    void roomWithoutATaskIdIsRefused() {
        MessageAuthorizationContext<byte[]> context = new MessageAuthorizationContext<>(subscribe(), Map.of());

        assertThat(authorization.authorize(() -> user(USER_ID), context).isGranted()).isFalse();
        verifyNoInteractions(userStatusLookup, taskRepository);
    }

    @Test
    void tokenWithoutAUserIdIsRefused() {
        Jwt withoutUid = Jwt.withTokenValue("token").header("alg", "HS256").subject("someone").build();

        assertThat(isGranted(new JwtAuthenticationToken(withoutUid), TASK_ID.toString())).isFalse();
        verifyNoInteractions(userStatusLookup, taskRepository);
    }

    @Test
    void anonymousSubscriberIsRefused() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(isGranted(anonymous, TASK_ID.toString())).isFalse();
        verifyNoInteractions(userStatusLookup, taskRepository);
    }

    private boolean isGranted(Authentication authentication, String taskId) {
        MessageAuthorizationContext<byte[]> context =
                new MessageAuthorizationContext<>(subscribe(), Map.of(TaskRoomAuthorization.TASK_ID, taskId));

        return authorization.authorize(() -> authentication, context).isGranted();
    }

    private static Message<byte[]> subscribe() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(TaskRooms.TOPIC_PREFIX + TASK_ID);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static JwtAuthenticationToken user(UUID id) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").claim("uid", id.toString()).build();

        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }
}
