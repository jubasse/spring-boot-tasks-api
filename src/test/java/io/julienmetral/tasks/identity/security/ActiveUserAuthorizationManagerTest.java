package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveUserAuthorizationManagerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private CurrentUser currentUser;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ActiveUserAuthorizationManager manager;

    private final Authentication authentication = mock(Authentication.class);
    private final RequestAuthorizationContext context = mock(RequestAuthorizationContext.class);

    private AuthorizationResult authorize() {
        return manager.authorize(() -> authentication, context);
    }

    private void stubUser(boolean enabled, Instant emailVerifiedAt) {
        User user = new User();
        user.setId(USER_ID);
        user.setEnabled(enabled);
        user.setEmailVerifiedAt(emailVerifiedAt);
        when(currentUser.getId(authentication)).thenReturn(Optional.of(USER_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void grantsEnabledVerifiedUser() {
        stubUser(true, Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(authorize().isGranted()).isTrue();
    }

    @Test
    void deniesUnverifiedUser() {
        stubUser(true, null);

        assertThat(authorize().isGranted()).isFalse();
    }

    @Test
    void deniesDisabledUser() {
        stubUser(false, Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(authorize().isGranted()).isFalse();
    }

    @Test
    void deniesDeletedOrUnknownUser() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(USER_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(authorize().isGranted()).isFalse();
    }

    @Test
    void deniesWhenNoUserIdCanBeResolved() {
        when(currentUser.getId(authentication)).thenReturn(Optional.empty());

        assertThat(authorize().isGranted()).isFalse();
        verifyNoInteractions(userRepository);
    }
}
