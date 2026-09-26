package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.PasswordResetToken;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.InvalidPasswordResetTokenException;
import io.julienmetral.tasks.identity.mail.PasswordResetProperties;
import io.julienmetral.tasks.identity.mail.PasswordResetRequested;
import io.julienmetral.tasks.identity.repositories.PasswordResetTokenRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.identity.security.OpaqueTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Emails a reset link to an enabled account. Unknown and disabled emails are silently ignored, so the
     * endpoint does not reveal which accounts exist.
     */
    @Transactional
    public void request(String email) {
        userRepository
                .findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
                .filter(User::isEnabled)
                .ifPresent(this::issue);
    }

    /**
     * Sets the new password and signs the user out everywhere. Following the emailed link proves ownership of the
     * address, so the email is marked as verified too.
     */
    @Transactional
    public void confirm(String rawToken, String newPassword) {
        Instant now = Instant.now();

        PasswordResetToken token = tokenRepository
                .findByTokenHash(OpaqueTokens.hash(rawToken))
                .filter(candidate -> candidate.getUsedAt() == null)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(InvalidPasswordResetTokenException::new);

        // findById skips soft-deleted users; a disabled account cannot sign in, so it cannot reset either
        User user = userRepository
                .findById(token.getUser().getId())
                .filter(User::isEnabled)
                .orElseThrow(InvalidPasswordResetTokenException::new);

        token.setUsedAt(now);

        user.setPasswordHash(passwordEncoder.encode(newPassword));

        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
        }

        tokenRepository.deleteUnusedForUser(user.getId());
        refreshTokenService.revokeAllForUser(user.getId());
    }

    private void issue(User user) {
        tokenRepository.deleteUnusedForUser(user.getId());

        String value = OpaqueTokens.generate();
        Instant now = Instant.now();

        PasswordResetToken token = new PasswordResetToken();

        token.setUser(userSummaryRepository.getReferenceById(user.getId()));
        token.setTokenHash(OpaqueTokens.hash(value));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(properties.ttl()));

        tokenRepository.save(token);

        eventPublisher.publishEvent(
                new PasswordResetRequested(
                        user.getEmail(),
                        user.getDisplayName(),
                        value,
                        token.getExpiresAt()
                )
        );
    }
}
