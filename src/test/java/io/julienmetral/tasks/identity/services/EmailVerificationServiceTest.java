package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.EmailVerificationToken;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.exceptions.EmailAlreadyVerifiedException;
import io.julienmetral.tasks.identity.exceptions.InvalidEmailVerificationTokenException;
import io.julienmetral.tasks.identity.exceptions.UserNotFoundException;
import io.julienmetral.tasks.identity.mail.EmailVerificationProperties;
import io.julienmetral.tasks.identity.mail.EmailVerificationRequested;
import io.julienmetral.tasks.identity.repositories.EmailVerificationTokenRepository;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.identity.security.OpaqueTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserProfiles.reference;
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
class EmailVerificationServiceTest {

    private static final Duration TTL = Duration.ofHours(24);

    private static final String RAW_TOKEN = "raw-verification-token";

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private EmailVerificationTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(
                tokenRepository,
                userRepository,
                userProfileRepository,
                new EmailVerificationProperties(TTL, "https://app.example.com/verify-email"),
                eventPublisher
        );
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("jane@example.com");
        user.setDisplayName("Jane");
        return user;
    }

    private static EmailVerificationToken storedToken(UserProfile owner, Instant expiresAt, Instant usedAt) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(owner);
        token.setTokenHash(OpaqueTokens.hash(RAW_TOKEN));
        token.setCreatedAt(Instant.now().minus(Duration.ofHours(1)));
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);
        return token;
    }

    private EmailVerificationToken stubStored(UserProfile owner, Instant expiresAt, Instant usedAt) {
        EmailVerificationToken token = storedToken(owner, expiresAt, usedAt);
        when(tokenRepository.findByTokenHash(OpaqueTokens.hash(RAW_TOKEN))).thenReturn(Optional.of(token));
        return token;
    }

    private UserProfile stubReference() {
        UserProfile reference = reference(USER_ID);
        when(userProfileRepository.getReferenceById(USER_ID)).thenReturn(reference);
        return reference;
    }

    private void assertIssuedFor(UserProfile reference, Instant before, Instant after) {
        InOrder order = inOrder(tokenRepository, eventPublisher);
        order.verify(tokenRepository).deleteUnusedForUser(USER_ID);

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        order.verify(tokenRepository).save(saved.capture());

        ArgumentCaptor<EmailVerificationRequested> event = ArgumentCaptor.forClass(EmailVerificationRequested.class);
        order.verify(eventPublisher).publishEvent(event.capture());

        EmailVerificationToken token = saved.getValue();
        EmailVerificationRequested published = event.getValue();

        assertThat(token.getUser()).isSameAs(reference);
        assertThat(token.getUsedAt()).isNull();
        assertThat(token.getCreatedAt()).isBetween(before, after);
        assertThat(token.getExpiresAt()).isEqualTo(token.getCreatedAt().plus(TTL));

        assertThat(published.userId()).isEqualTo(USER_ID);
        assertThat(published.email()).isEqualTo("jane@example.com");
        assertThat(published.displayName()).isEqualTo("Jane");
        assertThat(published.expiresAt()).isEqualTo(token.getExpiresAt());
        // The event carries the raw token; only its hash is stored
        assertThat(published.token()).hasSize(43);
        assertThat(token.getTokenHash()).isEqualTo(OpaqueTokens.hash(published.token()));
    }

    @Test
    void issueDeletesPendingTokensSavesHashAndPublishesEvent() {
        UserProfile reference = stubReference();
        Instant before = Instant.now();

        service.issue(user());

        assertIssuedFor(reference, before, Instant.now());
    }

    @Test
    void verifyUnknownTokenThrows() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(RAW_TOKEN))
                .isInstanceOf(InvalidEmailVerificationTokenException.class)
                .hasMessage("The email verification token is invalid, expired or already used");

        verify(tokenRepository).findByTokenHash(OpaqueTokens.hash(RAW_TOKEN));
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyUsedTokenThrowsAndKeepsOriginalUsage() {
        Instant usedAt = Instant.now().minusSeconds(60);
        EmailVerificationToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), usedAt);

        assertThatThrownBy(() -> service.verify(RAW_TOKEN))
                .isInstanceOf(InvalidEmailVerificationTokenException.class);

        assertThat(token.getUsedAt()).isEqualTo(usedAt);
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyExpiredTokenThrows() {
        EmailVerificationToken token = stubStored(reference(USER_ID), Instant.now().minusSeconds(1), null);

        assertThatThrownBy(() -> service.verify(RAW_TOKEN))
                .isInstanceOf(InvalidEmailVerificationTokenException.class);

        assertThat(token.getUsedAt()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyTokenOfDeletedUserThrowsWithoutConsumingIt() {
        EmailVerificationToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(RAW_TOKEN))
                .isInstanceOf(InvalidEmailVerificationTokenException.class);

        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void verifyValidTokenMarksItUsedAndVerifiesEmail() {
        User loaded = user();
        EmailVerificationToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(loaded));
        Instant before = Instant.now();

        service.verify(RAW_TOKEN);

        assertThat(token.getUsedAt()).isBetween(before, Instant.now());
        assertThat(loaded.getEmailVerifiedAt()).isEqualTo(token.getUsedAt());
    }

    @Test
    void verifyValidTokenDoesNotOverwriteExistingVerificationDate() {
        Instant verifiedAt = Instant.parse("2020-01-01T00:00:00Z");
        User user = user();
        user.setEmailVerifiedAt(verifiedAt);
        EmailVerificationToken token = stubStored(reference(USER_ID), Instant.now().plus(TTL), null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.verify(RAW_TOKEN);

        assertThat(token.getUsedAt()).isNotNull();
        assertThat(user.getEmailVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    void resendUnknownUserThrowsUserNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resend(USER_ID))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(USER_ID.toString());

        verifyNoInteractions(tokenRepository, eventPublisher);
    }

    @Test
    void resendAlreadyVerifiedUserThrows() {
        User user = user();
        user.setEmailVerifiedAt(Instant.parse("2020-01-01T00:00:00Z"));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.resend(USER_ID))
                .isInstanceOf(EmailAlreadyVerifiedException.class)
                .hasMessage("The email address is already verified");

        verifyNoInteractions(tokenRepository, eventPublisher);
    }

    @Test
    void resendUnverifiedUserIssuesNewToken() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        UserProfile reference = stubReference();
        Instant before = Instant.now();

        service.resend(USER_ID);

        assertIssuedFor(reference, before, Instant.now());
        verify(tokenRepository, never()).findByTokenHash(any());
    }
}
