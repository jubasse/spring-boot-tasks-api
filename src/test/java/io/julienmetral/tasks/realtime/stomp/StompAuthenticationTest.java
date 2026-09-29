package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompAuthenticationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String SESSION_ID = "session-1";

    private static final String TOKEN = "header.claims.signature";

    private static final Instant ISSUED_AT = Instant.parse("2026-03-04T05:00:00Z");

    private static final Instant EXPIRES_AT = Instant.parse("2026-03-04T05:15:00Z");

    private enum InactiveAccount {
        UNVERIFIED(new AccountState(true, null)),
        DISABLED(new AccountState(false, ISSUED_AT));

        private final AccountState state;

        InactiveAccount(AccountState state) {
            this.state = state;
        }
    }

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StompSessions stompSessions;

    @Mock
    private MessageChannel channel;

    private StompAuthentication authentication;

    @BeforeEach
    void createAuthentication() {
        authentication = new StompAuthentication(
                jwtDecoder, jwtAuthenticationConverter(), new CurrentUser(), userRepository, stompSessions);
    }

    @Test
    void connectOfAnActiveAccountAuthenticatesTheSessionWithTheRolesOfItsToken() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt(USER_ID.toString()));
        when(userRepository.findAccountStateById(USER_ID))
                .thenReturn(Optional.of(new AccountState(true, ISSUED_AT)));

        Message<?> connected = authentication.preSend(connect("Bearer " + TOKEN), channel);

        assertThat(accessorOf(connected).getUser()).isInstanceOfSatisfying(JwtAuthenticationToken.class, user -> {
            assertThat(user.getToken().getTokenValue()).isEqualTo(TOKEN);
            assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .contains("ROLE_USER", "ROLE_ADMIN");
        });
    }

    @Test
    void connectOfAnActiveAccountHandsTheSessionToTheSessionsUntilItsTokenExpires() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt(USER_ID.toString()));
        when(userRepository.findAccountStateById(USER_ID))
                .thenReturn(Optional.of(new AccountState(true, ISSUED_AT)));

        authentication.preSend(connect("Bearer " + TOKEN), channel);

        verify(stompSessions).connected(SESSION_ID, USER_ID, EXPIRES_AT);
    }

    @Test
    void acceptedConnectNoLongerCarriesTheToken() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt(USER_ID.toString()));
        when(userRepository.findAccountStateById(USER_ID))
                .thenReturn(Optional.of(new AccountState(true, ISSUED_AT)));

        Message<?> connected = authentication.preSend(connect("Bearer " + TOKEN), channel);

        assertThat(accessorOf(connected).getNativeHeader("Authorization")).isNull();
    }

    @Test
    void refusedConnectNoLongerCarriesTheToken() {
        when(jwtDecoder.decode(TOKEN)).thenThrow(new BadJwtException("Signed JWT rejected"));
        Message<byte[]> connect = connect("Bearer " + TOKEN);

        assertThatThrownBy(() -> authentication.preSend(connect, channel))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(accessorOf(connect).getNativeHeader("Authorization")).isNull();
    }

    @Test
    void connectWithoutAnAuthorizationHeaderIsRefusedAsBadCredentials() {
        assertThatThrownBy(() -> authentication.preSend(connect(null), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("A bearer access token is required");
        verifyNoInteractions(jwtDecoder, userRepository, stompSessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNzd29yZA==", TOKEN})
    void connectWithAnotherAuthorizationThanABearerTokenIsRefusedAsBadCredentials(String authorization) {
        assertThatThrownBy(() -> authentication.preSend(connect(authorization), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("A bearer access token is required");
        verifyNoInteractions(jwtDecoder, userRepository, stompSessions);
    }

    @Test
    void connectWithATokenTheDecoderRejectsIsRefusedAsBadCredentials() {
        BadJwtException rejected = new BadJwtException("Signed JWT rejected");
        when(jwtDecoder.decode(TOKEN)).thenThrow(rejected);

        assertThatThrownBy(() -> authentication.preSend(connect("Bearer " + TOKEN), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid access token")
                .hasCause(rejected);
        verifyNoInteractions(userRepository, stompSessions);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "not-a-uuid")
    void connectWithATokenWithoutAUserIdIsRefusedAsBadCredentials(String uid) {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt(uid));

        assertThatThrownBy(() -> authentication.preSend(connect("Bearer " + TOKEN), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("No user id in the access token");
        verifyNoInteractions(userRepository, stompSessions);
    }

    @ParameterizedTest
    @EnumSource(InactiveAccount.class)
    void connectOfAnAccountThatIsNotActiveIsRefused(InactiveAccount account) {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt(USER_ID.toString()));
        when(userRepository.findAccountStateById(USER_ID)).thenReturn(Optional.of(account.state));
        Message<byte[]> connect = connect("Bearer " + TOKEN);

        assertThatThrownBy(() -> authentication.preSend(connect, channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("The account is not active");
        assertThat(accessorOf(connect).getUser()).isNull();
        verifyNoInteractions(stompSessions);
    }

    @Test
    void connectOfADeletedAccountIsRefused() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt(USER_ID.toString()));
        when(userRepository.findAccountStateById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authentication.preSend(connect("Bearer " + TOKEN), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("The account is not active");
        verifyNoInteractions(stompSessions);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SUBSCRIBE", "SEND", "UNSUBSCRIBE", "DISCONNECT"})
    void framesOtherThanConnectPassWithoutAToken(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        Message<byte[]> frame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(authentication.preSend(frame, channel)).isSameAs(frame);
        verifyNoInteractions(jwtDecoder, userRepository, stompSessions);
    }

    private static Message<byte[]> connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(SESSION_ID);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        // As the STOMP handler leaves them, so that interceptors can set the user
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static StompHeaderAccessor accessorOf(Message<?> message) {
        return MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    }

    private static Jwt jwt(String uid) {
        Jwt.Builder jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "HS256")
                .issuer("tasks-api")
                .issuedAt(ISSUED_AT)
                .expiresAt(EXPIRES_AT)
                .claim("roles", List.of("ROLE_ADMIN", "ROLE_USER"));

        return uid == null ? jwt.build() : jwt.claim("uid", uid).build();
    }

    // As SecurityConfiguration declares it: the roles claim already carries the ROLE_ prefix
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return converter;
    }
}
