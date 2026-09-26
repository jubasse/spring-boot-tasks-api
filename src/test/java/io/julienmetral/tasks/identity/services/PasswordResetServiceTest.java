package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.PasswordResetToken;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.identity.exceptions.InvalidPasswordResetTokenException;
import io.julienmetral.tasks.identity.mail.PasswordResetProperties;
import io.julienmetral.tasks.identity.mail.PasswordResetRequested;
import io.julienmetral.tasks.identity.repositories.PasswordResetTokenRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.identity.security.OpaqueTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Duration TTL = Duration.ofHours(1);

    private static final String RAW_TOKEN = "raw-password-reset-token";

    private static final String NEW_PASSWORD = "new-password-123";

    private static final String ENCODED_PASSWORD = "{argon2}encoded-new-password";

    private static final String OLD_HASH = "{argon2}old-hash";

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSummaryRepository userSummaryRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(
                tokenRepository,
                userRepository,
                userSummaryRepository,
                passwordEncoder,
                refreshTokenService,
                new PasswordResetProperties(TTL, "https://app.example.com/reset-password"),
                eventPublisher
        );
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("jane@example.com");
        user.setDisplayName("Jane");
        user.setPasswordHash(OLD_HASH);
        user.setEnabled(true);
        return user;
    }

    private PasswordResetToken stubStored(UserSummary owner, Instant expiresAt, Instant usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(owner);
        token.setTokenHash(OpaqueTokens.hash(RAW_TOKEN));
        token.setCreatedAt(Instant.now().minus(Duration.ofMinutes(10)));
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);

        when(tokenRepository.findByTokenHash(OpaqueTokens.hash(RAW_TOKEN))).thenReturn(Optional.of(token));

        return token;
    }

    private void assertNoSideEffects() {
        verifyNoInteractions(passwordEncoder, refreshTokenService);
        verify(tokenRepository, never()).deleteUnusedForUser(any());
    }

    private void assertNothingChanged(User user) {
        assertThat(user.getPasswordHash()).isEqualTo(OLD_HASH);
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertNoSideEffects();
    }

    // --- request ---

    @Test
    void requestForEnabledUserReplacesPendingTokensStoresHashAndPublishesEvent() {
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user()));
        UserSummary reference = reference(USER_ID);
        when(userSummaryRepository.getReferenceById(USER_ID)).thenReturn(reference);
        Instant before = Instant.now();

        service.request("  Jane@Example.COM ");

        Instant after = Instant.now();

        InOrder order = inOrder(tokenRepository, eventPublisher);
        order.verify(tokenRepository).deleteUnusedForUser(USER_ID);

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        order.verify(tokenRepository).save(saved.capture());

        ArgumentCaptor<PasswordResetRequested> event = ArgumentCaptor.forClass(PasswordResetRequested.class);
        order.verify(eventPublisher).publishEvent(event.capture());

        PasswordResetToken token = saved.getValue();
        PasswordResetRequested published = event.getValue();

        assertThat(token.getUser()).isSameAs(reference);
        assertThat(token.getUsedAt()).isNull();
        assertThat(token.getCreatedAt()).isBetween(before, after);
        assertThat(token.getExpiresAt()).isEqualTo(token.getCreatedAt().plus(TTL));

        assertThat(published.email()).isEqualTo("jane@example.com");
        assertThat(published.displayName()).isEqualTo("Jane");
        assertThat(published.expiresAt()).isEqualTo(token.getExpiresAt());
        // The event carries the raw token; only its hash is stored
        assertThat(published.token()).hasSize(43);
        assertThat(token.getTokenHash())
                .isEqualTo(OpaqueTokens.hash(published.token()))
                .isNotEqualTo(published.token());
    }

    @Test
    void requestForUnknownEmailDoesNothing() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        service.request("nobody@example.com");

        verifyNoInteractions(tokenRepository, eventPublisher);
    }

    @Test
    void requestForDisabledUserDoesNothing() {
        User user = user();
        user.setEnabled(false);
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));

        service.request("jane@example.com");

        verifyNoInteractions(tokenRepository, userSummaryRepository, eventPublisher);
    }

    // --- confirm: rejected tokens ---

    @Test
    void confirmUnknownTokenThrows() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(RAW_TOKEN, NEW_PASSWORD))
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .hasMessage("The password reset token is invalid, expired or already used");

        // Looked up by hash, never by the raw value
        verify(tokenRepository).findByTokenHash(OpaqueTokens.hash(RAW_TOKEN));
        verifyNoInteractions(userRepository, passwordEncoder, refreshTokenService);
    }

    @Test
    void confirmUsedTokenThrowsAndKeepsOriginalUsage() {
        Instant usedAt = Instant.now().minusSeconds(60);
        PasswordResetToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), usedAt);

        assertThatThrownBy(() -> service.confirm(RAW_TOKEN, NEW_PASSWORD))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertThat(token.getUsedAt()).isEqualTo(usedAt);
        verifyNoInteractions(userRepository);
        assertNoSideEffects();
    }

    @Test
    void confirmExpiredTokenThrows() {
        PasswordResetToken token = stubStored(reference(USER_ID), Instant.now().minusSeconds(1), null);

        assertThatThrownBy(() -> service.confirm(RAW_TOKEN, NEW_PASSWORD))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertThat(token.getUsedAt()).isNull();
        verifyNoInteractions(userRepository);
        assertNoSideEffects();
    }

    @Test
    void confirmTokenOfSoftDeletedUserThrowsWithoutConsumingIt() {
        PasswordResetToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(RAW_TOKEN, NEW_PASSWORD))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertThat(token.getUsedAt()).isNull();
        assertNoSideEffects();
    }

    @Test
    void confirmTokenOfDisabledUserThrowsWithoutConsumingIt() {
        User user = user();
        user.setEnabled(false);
        PasswordResetToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.confirm(RAW_TOKEN, NEW_PASSWORD))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertThat(token.getUsedAt()).isNull();
        assertNothingChanged(user);
    }

    // --- confirm: success ---

    @Test
    void confirmValidTokenSetsPasswordVerifiesEmailAndSignsOutEverywhere() {
        // The token holds a lazy reference; the service works on the user loaded by id
        User loaded = user();
        PasswordResetToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(loaded));
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        Instant before = Instant.now();

        service.confirm(RAW_TOKEN, NEW_PASSWORD);

        assertThat(token.getUsedAt()).isBetween(before, Instant.now());
        assertThat(loaded.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
        assertThat(loaded.getEmailVerifiedAt()).isEqualTo(token.getUsedAt());

        InOrder order = inOrder(tokenRepository, refreshTokenService);
        order.verify(tokenRepository).deleteUnusedForUser(USER_ID);
        order.verify(refreshTokenService).revokeAllForUser(USER_ID);
    }

    @Test
    void confirmValidTokenKeepsExistingVerificationDate() {
        Instant verifiedAt = Instant.parse("2020-01-01T00:00:00Z");
        User user = user();
        user.setEmailVerifiedAt(verifiedAt);
        PasswordResetToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

        service.confirm(RAW_TOKEN, NEW_PASSWORD);

        assertThat(token.getUsedAt()).isNotNull();
        assertThat(user.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
        assertThat(user.getEmailVerifiedAt()).isEqualTo(verifiedAt);
        verify(tokenRepository).deleteUnusedForUser(USER_ID);
        verify(refreshTokenService).revokeAllForUser(USER_ID);
    }
}
