package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.RefreshToken;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.repositories.RefreshTokenRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.OpaqueTokens;
import io.julienmetral.tasks.identity.security.RefreshTokenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenProperties properties;

    /** Starts a new token family, one per login. */
    @Transactional
    public IssuedRefreshToken issue(User user) {
        return create(user, UUID.randomUUID(), Instant.now());
    }

    /**
     * Revokes the presented token and issues its successor in the same family.
     * <p>
     * Revocations made before throwing must be committed, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RotatedRefreshToken rotate(String rawToken) {
        Instant now = Instant.now();

        RefreshToken current = refreshTokenRepository
                .findByTokenHash(OpaqueTokens.hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (current.getRevokedAt() != null) {
            // A revoked token used again has leaked: revoke every token of that login
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now);

            throw new InvalidRefreshTokenException();
        }

        if (!current.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }

        // The user is null when soft-deleted; disabled users cannot refresh either
        User user = Optional
                .ofNullable(current.getUser())
                .map(User::getId)
                .flatMap(userRepository::findById)
                .filter(User::isEnabled)
                .orElse(null);

        if (user == null) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now);

            throw new InvalidRefreshTokenException();
        }

        current.setRevokedAt(now);

        return new RotatedRefreshToken(
                user,
                create(user, current.getFamilyId(), now)
        );
    }

    /** Logout: revokes the token's whole family. Unknown tokens are ignored so the endpoint reveals nothing. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository
                .findByTokenHash(OpaqueTokens.hash(rawToken))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    /** Called when a user is disabled or deleted, so none of their sessions can be refreshed. */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    private IssuedRefreshToken create(User user, UUID familyId, Instant now) {
        String value = OpaqueTokens.generate();

        RefreshToken token = new RefreshToken();

        token.setUser(user);
        token.setTokenHash(OpaqueTokens.hash(value));
        token.setFamilyId(familyId);
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(properties.ttl()));

        refreshTokenRepository.save(token);

        return new IssuedRefreshToken(value, token.getExpiresAt());
    }

    public record IssuedRefreshToken(
            String value,
            Instant expiresAt
    ) {
    }

    public record RotatedRefreshToken(
            User user,
            IssuedRefreshToken refreshToken
    ) {
    }
}
