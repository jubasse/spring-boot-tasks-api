package io.julienmetral.tasks.identity.security;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAuthorizationTest {

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private UserAuthorization userAuthorization;

    private final Authentication authentication = mock(Authentication.class);

    @Test
    void trueWhenCurrentUserIsTarget() {
        UUID id = UUID.randomUUID();
        when(currentUser.getId(authentication)).thenReturn(Optional.of(id));

        assertThat(userAuthorization.currentUserIsSelf(id, authentication)).isTrue();
    }

    @Test
    void falseWhenCurrentUserIsSomeoneElse() {
        when(currentUser.getId(authentication)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(userAuthorization.currentUserIsSelf(UUID.randomUUID(), authentication)).isFalse();
    }

    @Test
    void falseWhenNoCurrentUser() {
        when(currentUser.getId(authentication)).thenReturn(Optional.empty());

        assertThat(userAuthorization.currentUserIsSelf(UUID.randomUUID(), authentication)).isFalse();
    }
}
