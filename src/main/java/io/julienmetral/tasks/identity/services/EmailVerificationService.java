package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.EmailVerificationToken;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.EmailAlreadyVerifiedException;
import io.julienmetral.tasks.identity.exceptions.InvalidEmailVerificationTokenException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.mail.EmailVerificationProperties;
import io.julienmetral.tasks.identity.mail.EmailVerificationRequested;
import io.julienmetral.tasks.identity.repositories.EmailVerificationTokenRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.security.OpaqueTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final EmailVerificationProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /** Replaces any pending token with a new one and emails it after commit. */
    @Transactional
    public void issue(User user) {
        tokenRepository.deleteUnusedForUser(user.getId());

        String value = OpaqueTokens.generate();
        Instant now = Instant.now();

        EmailVerificationToken token = new EmailVerificationToken();

        token.setUser(userProfileRepository.getReferenceById(user.getId()));
        token.setTokenHash(OpaqueTokens.hash(value));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(properties.ttl()));

        tokenRepository.save(token);

        eventPublisher.publishEvent(
                new EmailVerificationRequested(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName(),
                        value,
                        token.getExpiresAt()
                )
        );
    }

    @Transactional
    public void verify(String rawToken) {
        Instant now = Instant.now();

        EmailVerificationToken token = tokenRepository
                .findByTokenHash(OpaqueTokens.hash(rawToken))
                .filter(candidate -> candidate.getUsedAt() == null)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(InvalidEmailVerificationTokenException::new);

        // findById skips soft-deleted users
        User user = userRepository
                .findByIdForUpdate(token.getUser().getId())
                .orElseThrow(InvalidEmailVerificationTokenException::new);

        token.setUsedAt(now);

        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
        }
    }

    @Transactional
    public void resend(UUID userId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getEmailVerifiedAt() != null) {
            throw new EmailAlreadyVerifiedException();
        }

        issue(user);
    }
}
