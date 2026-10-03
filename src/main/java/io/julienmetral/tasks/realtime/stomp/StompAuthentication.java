package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Authenticates the CONNECT frame with the access token of its {@code Authorization} header, as the HTTP API does:
 * a browser sends no header with the WebSocket handshake, so the frame carries it. The session keeps that user until
 * it closes; {@link StompSessions} closes it when the token expires or the account stops being active.
 * <p>
 * A refusal answers an ERROR frame and closes the connection.
 */
@Component
@RequiredArgsConstructor
class StompAuthentication implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final StompSessions stompSessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        // Out of the frame before anything logs it: debug logs print native headers
        accessor.removeNativeHeader(HttpHeaders.AUTHORIZATION);

        // The scheme is case-insensitive (RFC 9110), as the HTTP API's resolver treats it
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new BadCredentialsException("A bearer access token is required");
        }

        Jwt jwt = decode(authorization.substring(BEARER.length()));
        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);
        UUID userId = currentUser.getId(authentication)
                .orElseThrow(() -> new BadCredentialsException("No user id in the access token"));

        // Registered before the account is read, from the account rather than the status cache: a change committed
        // after this read is broadcast, and closes the session once it arrives. Read first, a disabling that landed
        // between the read and the registration found no session to close, which then lasted until its token expired.
        String sessionId = accessor.getSessionId();
        stompSessions.connected(sessionId, userId, jwt.getExpiresAt());

        boolean active;

        try {
            active = isActive(userId);
        } catch (RuntimeException readFailed) {
            stompSessions.forget(sessionId);
            throw readFailed;
        }

        if (!active) {
            stompSessions.forget(sessionId);
            throw new AccessDeniedException("The account is not active");
        }

        accessor.setUser(authentication);

        return message;
    }

    private Jwt decode(String token) {
        try {
            return jwtDecoder.decode(token);
        } catch (JwtException invalid) {
            throw new BadCredentialsException("Invalid access token", invalid);
        }
    }

    private boolean isActive(UUID userId) {
        return userRepository.findAccountStateById(userId)
                .map(AccountState::status)
                .orElse(UserStatus.DELETED) == UserStatus.ACTIVE;
    }
}
