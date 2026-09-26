package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.exceptions.UserEmailAlreadyExistsException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public User create(
            String email,
            String rawPassword,
            String displayName
    ) {
        String normalizedEmail = normalizeEmail(email);

        if (userRepository.existsByEmailIncludingDeleted(normalizedEmail)) {
            throw new UserEmailAlreadyExistsException(normalizedEmail);
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName.trim());
        user.setEnabled(true);
        user.setRoles(new HashSet<>(Set.of(UserRole.USER)));

        User saved = userRepository.save(user);

        emailVerificationService.issue(saved);

        return saved;
    }

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public User findByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    @Transactional
    public User updateProfile(UUID id, String displayName) {
        User user = getUser(id);

        // displayName is optional in PATCH: a missing or blank value keeps the current one
        if (StringUtils.hasText(displayName)) {
            user.setDisplayName(displayName.trim());
        }

        return user;
    }

    @Transactional
    public void changePassword(UUID id, String rawPassword) {
        User user = getUser(id);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
    }

    @Transactional
    public void verifyEmail(UUID id) {
        User user = getUser(id);

        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
        }
    }

    @Transactional
    public void recordLogin(UUID id) {
        getUser(id).setLastLoginAt(Instant.now());
    }

    @Transactional
    public void enable(UUID id) {
        getUser(id).setEnabled(true);
    }

    @Transactional
    public void disable(UUID id) {
        getUser(id).setEnabled(false);

        // Access tokens expire on their own; refresh tokens must stop working now
        refreshTokenService.revokeAllForUser(id);
    }

    @Transactional
    public void addRole(UUID id, UserRole role) {
        getUser(id).getRoles().add(role);
    }

    @Transactional
    public void removeRole(UUID id, UserRole role) {
        User user = getUser(id);

        if (role == UserRole.USER) {
            return;
        }

        user.getRoles().remove(role);
    }

    @Transactional
    public void delete(UUID id) {
        User user = getUser(id);

        user.markDeleted();
        userRepository.delete(user);

        refreshTokenService.revokeAllForUser(id);
    }

    private User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
