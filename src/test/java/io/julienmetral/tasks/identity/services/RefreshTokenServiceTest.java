package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.RefreshToken;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.repositories.RefreshTokenRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.security.OpaqueTokens;
import io.julienmetral.tasks.identity.security.RefreshTokenProperties;
import io.julienmetral.tasks.identity.services.RefreshTokenService.IssuedRefreshToken;
import io.julienmetral.tasks.identity.services.RefreshTokenService.RotatedRefreshToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserProfiles.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T08:30:00Z");

    private static final Duration TTL = Duration.ofDays(30);

    private static final String RAW_TOKEN = "raw-refresh-token";

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(
                refreshTokenRepository,
                userRepository,
                userProfileRepository,
                new RefreshTokenProperties(TTL),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("jane@example.com");
        user.setEnabled(true);
        return user;
    }

    private static RefreshToken storedToken(UserProfile owner, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(owner);
        token.setTokenHash(OpaqueTokens.hash(RAW_TOKEN));
        token.setFamilyId(FAMILY_ID);
        token.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        return token;
    }

    private RefreshToken stubStored(UserProfile owner, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = storedToken(owner, expiresAt, revokedAt);
        when(refreshTokenRepository.findByTokenHash(OpaqueTokens.hash(RAW_TOKEN))).thenReturn(Optional.of(token));
        return token;
    }

    private UserProfile stubReference() {
        UserProfile reference = reference(USER_ID);
        when(userProfileRepository.getReferenceById(USER_ID)).thenReturn(reference);
        return reference;
    }

    private RefreshToken captureSaved() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void issueSavesHashedTokenInNewFamilyAndReturnsRawValue() {
        UserProfile reference = stubReference();

        IssuedRefreshToken issued = service.issue(user());

        RefreshToken saved = captureSaved();

        assertThat(issued.value()).hasSize(43);
        assertThat(saved.getTokenHash()).isEqualTo(OpaqueTokens.hash(issued.value())).isNotEqualTo(issued.value());
        assertThat(saved.getUser()).isSameAs(reference);
        assertThat(saved.getFamilyId()).isNotNull();
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getExpiresAt()).isEqualTo(saved.getCreatedAt().plus(TTL));
        assertThat(issued.expiresAt()).isEqualTo(saved.getExpiresAt());
    }

    @Test
    void issueStartsADistinctFamilyOnEveryLogin() {
        stubReference();
        User user = user();

        IssuedRefreshToken first = service.issue(user);
        IssuedRefreshToken second = service.issue(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(captor.capture());

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(captor.getAllValues().get(0).getFamilyId())
                .isNotEqualTo(captor.getAllValues().get(1).getFamilyId());
    }

    @Test
    void rotateUnknownTokenThrowsWithoutRevokingAnything() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("The refresh token is invalid, expired or revoked");

        verify(refreshTokenRepository).findByTokenHash(OpaqueTokens.hash(RAW_TOKEN));
        verifyNoMoreInteractions(refreshTokenRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    void rotateRevokedTokenRevokesWholeFamilyAndThrows() {
        stubStored(reference(USER_ID), NOW.plus(TTL), NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN)).isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).revokeFamily(FAMILY_ID, NOW);
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void rotateExpiredTokenThrowsWithoutRevokingFamily() {
        RefreshToken token = stubStored(reference(USER_ID), NOW.minusSeconds(1), null);

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN)).isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(userRepository);
        assertThat(token.getRevokedAt()).isNull();
    }

    @Test
    void rotateTokenOfSoftDeletedUserRevokesWholeFamilyAndThrows() {
        RefreshToken token = stubStored(reference(USER_ID), NOW.plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN)).isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).revokeFamily(FAMILY_ID, NOW);
        verify(refreshTokenRepository, never()).save(any());
        assertThat(token.getRevokedAt()).isNull();
    }

    @Test
    void rotateTokenOfDisabledUserRevokesWholeFamilyAndThrows() {
        User disabled = user();
        disabled.setEnabled(false);
        stubStored(reference(USER_ID), NOW.plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN)).isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).revokeFamily(FAMILY_ID, NOW);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotateValidTokenRevokesItAndIssuesSuccessorInSameFamily() {
        // The token holds a lazy reference; the returned user is the one reloaded from the database
        User loaded = user();
        RefreshToken current = stubStored(reference(USER_ID), NOW.plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(loaded));
        UserProfile successorReference = stubReference();

        RotatedRefreshToken rotated = service.rotate(RAW_TOKEN);

        RefreshToken successor = captureSaved();

        assertThat(rotated.user()).isSameAs(loaded);
        assertThat(current.getRevokedAt()).isEqualTo(NOW);

        assertThat(successor).isNotSameAs(current);
        assertThat(successor.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(successor.getUser()).isSameAs(successorReference);
        assertThat(successor.getRevokedAt()).isNull();
        assertThat(successor.getCreatedAt()).isEqualTo(current.getRevokedAt());
        assertThat(successor.getExpiresAt()).isEqualTo(successor.getCreatedAt().plus(TTL));
        assertThat(successor.getTokenHash()).isEqualTo(OpaqueTokens.hash(rotated.refreshToken().value()));

        assertThat(rotated.refreshToken().value()).isNotEqualTo(RAW_TOKEN).hasSize(43);
        assertThat(rotated.refreshToken().expiresAt()).isEqualTo(successor.getExpiresAt());

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void revokeKnownTokenRevokesItsFamily() {
        stubStored(reference(USER_ID), NOW.plus(TTL), null);

        service.revoke(RAW_TOKEN);

        verify(refreshTokenRepository).revokeFamily(FAMILY_ID, NOW);
    }

    @Test
    void revokeUnknownTokenIsIgnored() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.revoke(RAW_TOKEN);

        verify(refreshTokenRepository).findByTokenHash(OpaqueTokens.hash(RAW_TOKEN));
        verifyNoMoreInteractions(refreshTokenRepository);
    }

    @Test
    void revokeAllForUserDelegatesWithCurrentTime() {
        service.revokeAllForUser(USER_ID);

        verify(refreshTokenRepository).revokeAllForUser(USER_ID, NOW);
    }
}
