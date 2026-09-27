package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.entities.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ActiveUserAuthorizationManagerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private CurrentUser currentUser;

    @Mock
    private UserStatusLookup userStatusLookup;

    @InjectMocks
    private ActiveUserAuthorizationManager manager;

    private final Authentication authentication = mock(Authentication.class);
    private final RequestAuthorizationContext context = mock(RequestAuthorizationContext.class);

    private AuthorizationResult authorize() {
        return manager.authorize(() -> authentication, context);
    }

    private void stubStatus(UserStatus status) {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(USER_ID));
        when(userStatusLookup.statusOf(USER_ID)).thenReturn(status);
    }

    @Test
    void grantsActiveUserWithoutLoggingARefusal(CapturedOutput output) {
        stubStatus(UserStatus.ACTIVE);

        assertThat(authorize().isGranted()).isTrue();
        assertThat(output).doesNotContain("Task access denied");
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void deniesEveryOtherStatus(UserStatus status) {
        stubStatus(status);

        assertThat(authorize().isGranted()).isFalse();
    }

    @Test
    void refusalIsLoggedWithTheAccountAndItsStatus(CapturedOutput output) {
        stubStatus(UserStatus.DISABLED);

        authorize();

        assertThat(output).contains("Task access denied to account " + USER_ID + ", which is DISABLED");
    }

    @Test
    void deniesWhenNoUserIdCanBeResolved() {
        when(currentUser.getId(authentication)).thenReturn(Optional.empty());

        assertThat(authorize().isGranted()).isFalse();
        verifyNoInteractions(userStatusLookup);
    }

    @Test
    void failingStatusLookupPropagatesInsteadOfGranting() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database down");
        when(currentUser.getId(authentication)).thenReturn(Optional.of(USER_ID));
        when(userStatusLookup.statusOf(USER_ID)).thenThrow(failure);

        assertThatThrownBy(this::authorize).isSameAs(failure);
    }
}
