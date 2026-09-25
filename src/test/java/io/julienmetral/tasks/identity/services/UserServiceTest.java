package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.exceptions.UserEmailAlreadyExistsException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private User existingUser() {
        User user = new User();
        user.setId(ID);
        user.setEmail("jane@example.com");
        user.setDisplayName("Jane");
        user.setPasswordHash("old-hash");
        user.setRoles(new HashSet<>(Set.of(UserRole.USER)));
        return user;
    }

    private User stubExisting() {
        User user = existingUser();
        when(userRepository.findById(ID)).thenReturn(Optional.of(user));
        return user;
    }

    private void stubMissing() {
        when(userRepository.findById(ID)).thenReturn(Optional.empty());
    }

    // --- create ---

    @Test
    void createNormalizesEmailTrimsDisplayNameEncodesPasswordAndAssignsUserRole() {
        when(userRepository.existsByEmailIncludingDeleted("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret-pw")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.create("  Jane@Example.COM  ", "secret-pw", "  Jane Doe  ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(result).isSameAs(saved);
        assertThat(saved.getEmail()).isEqualTo("jane@example.com");
        assertThat(saved.getDisplayName()).isEqualTo("Jane Doe");
        assertThat(saved.getPasswordHash()).isEqualTo("encoded");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getRoles()).containsExactly(UserRole.USER);
        // roles set must be mutable so addRole works later
        assertThatCode(() -> saved.getRoles().add(UserRole.ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void createWithDuplicateEmailThrowsAndDoesNotSave() {
        when(userRepository.existsByEmailIncludingDeleted("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(" JANE@example.com ", "pw", "Jane"))
                .isInstanceOf(UserEmailAlreadyExistsException.class)
                .hasMessageContaining("jane@example.com");

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    // --- findById / findByEmail ---

    @Test
    void findByIdReturnsUser() {
        User user = stubExisting();

        assertThat(userService.findById(ID)).isSameAs(user);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        stubMissing();

        assertThatThrownBy(() -> userService.findById(ID))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(ID.toString());
    }

    @Test
    void findByEmailNormalizesEmailBeforeLookup() {
        User user = existingUser();
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));

        assertThat(userService.findByEmail("  JANE@Example.com ")).isSameAs(user);
    }

    @Test
    void findByEmailThrowsWhenMissing() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findByEmail("nobody@example.com"))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("nobody@example.com");
    }

    // --- updateProfile ---

    @Test
    void updateProfileTrimsDisplayName() {
        User user = stubExisting();

        User result = userService.updateProfile(ID, "  New Name  ");

        assertThat(result).isSameAs(user);
        assertThat(user.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void updateProfileWithNullDisplayNameKeepsCurrentName() {
        User user = stubExisting();

        User result = userService.updateProfile(ID, null);

        assertThat(result.getDisplayName()).isEqualTo("Jane");
    }

    @Test
    void updateProfileWithBlankDisplayNameKeepsCurrentName() {
        User user = stubExisting();

        User result = userService.updateProfile(ID, "   ");

        assertThat(result.getDisplayName()).isEqualTo("Jane");
    }

    @Test
    void updateProfileThrowsWhenUserMissing() {
        stubMissing();

        assertThatThrownBy(() -> userService.updateProfile(ID, "Name"))
                .isInstanceOf(UserNotFoundException.class);
    }

    // --- changePassword ---

    @Test
    void changePasswordEncodesNewPassword() {
        User user = stubExisting();
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        userService.changePassword(ID, "new-password");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void changePasswordThrowsWhenUserMissing() {
        stubMissing();

        assertThatThrownBy(() -> userService.changePassword(ID, "pw"))
                .isInstanceOf(UserNotFoundException.class);
        verify(passwordEncoder, never()).encode(any());
    }

    // --- verifyEmail ---

    @Test
    void verifyEmailSetsTimestampWhenNotYetVerified() {
        User user = stubExisting();
        Instant before = Instant.now();

        userService.verifyEmail(ID);

        assertThat(user.getEmailVerifiedAt()).isNotNull().isBetween(before, Instant.now());
    }

    @Test
    void verifyEmailDoesNotOverwriteExistingTimestamp() {
        User user = stubExisting();
        Instant original = Instant.parse("2020-01-01T00:00:00Z");
        user.setEmailVerifiedAt(original);

        userService.verifyEmail(ID);

        assertThat(user.getEmailVerifiedAt()).isEqualTo(original);
    }

    // --- recordLogin ---

    @Test
    void recordLoginSetsLastLoginAt() {
        User user = stubExisting();
        Instant before = Instant.now();

        userService.recordLogin(ID);

        assertThat(user.getLastLoginAt()).isBetween(before, Instant.now());
    }

    // --- enable / disable ---

    @Test
    void enableSetsEnabledTrue() {
        User user = stubExisting();
        user.setEnabled(false);

        userService.enable(ID);

        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void disableSetsEnabledFalse() {
        User user = stubExisting();
        user.setEnabled(true);

        userService.disable(ID);

        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    void enableThrowsWhenUserMissing() {
        stubMissing();

        assertThatThrownBy(() -> userService.enable(ID)).isInstanceOf(UserNotFoundException.class);
    }

    // --- roles ---

    @Test
    void addRoleAddsRole() {
        User user = stubExisting();

        userService.addRole(ID, UserRole.ADMIN);

        assertThat(user.getRoles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    @Test
    void removeRoleRemovesNonUserRole() {
        User user = stubExisting();
        user.getRoles().add(UserRole.ADMIN);

        userService.removeRole(ID, UserRole.ADMIN);

        assertThat(user.getRoles()).containsExactly(UserRole.USER);
    }

    @Test
    void removeRoleIgnoresUserRole() {
        User user = stubExisting();

        userService.removeRole(ID, UserRole.USER);

        assertThat(user.getRoles()).containsExactly(UserRole.USER);
    }

    @Test
    void removeRoleThrowsWhenUserMissing() {
        stubMissing();

        assertThatThrownBy(() -> userService.removeRole(ID, UserRole.USER))
                .isInstanceOf(UserNotFoundException.class);
    }

    // --- delete ---

    @Test
    void deleteDeletesLoadedUser() {
        User user = stubExisting();

        userService.delete(ID);

        verify(userRepository).delete(user);
    }

    @Test
    void deleteThrowsWhenUserMissing() {
        stubMissing();

        assertThatThrownBy(() -> userService.delete(ID)).isInstanceOf(UserNotFoundException.class);
        verify(userRepository, never()).delete(any(User.class));
    }
}
