package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * The WebSocket sessions of this instance and their users, so that a session ends with its access token, and as soon
 * as its account is no longer active: once connected, a session is never authenticated again.
 */
@Slf4j
@Component
public class StompSessions {

    private record Connected(UUID userId, ScheduledFuture<?> expiry) {
    }

    // Policy violation: the client must not reconnect with the same credentials
    private static final CloseStatus TOKEN_EXPIRED = CloseStatus.POLICY_VIOLATION.withReason("Access token expired");
    private static final CloseStatus ACCOUNT_CLOSED = CloseStatus.POLICY_VIOLATION.withReason("Account not active");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Connected> connected = new ConcurrentHashMap<>();
    private final UserRepository userRepository;
    private final TaskScheduler scheduler;

    // Lazy: the broker's scheduler is built by the configuration that reads this class through StompConfiguration
    public StompSessions(
            UserRepository userRepository,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler
    ) {
        this.userRepository = userRepository;
        this.scheduler = scheduler;
    }

    WebSocketHandler track(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                forget(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** Called on the CONNECT frame, once its token is verified: the session ends when the token expires. */
    void connected(String sessionId, UUID userId, Instant tokenExpiresAt) {
        ScheduledFuture<?> expiry = scheduler.schedule(() -> close(sessionId, TOKEN_EXPIRED), tokenExpiresAt);
        Connected previous = connected.put(sessionId, new Connected(userId, expiry));

        if (previous != null) {
            previous.expiry().cancel(false);
        }
    }

    public void closeIfNoLongerActive(UUID userId) {
        boolean hasSessions = connected.values().stream().anyMatch(session -> session.userId().equals(userId));

        if (hasSessions && !isActive(userId)) {
            connected.forEach((sessionId, session) -> {
                if (session.userId().equals(userId)) {
                    close(sessionId, ACCOUNT_CLOSED);
                }
            });
        }
    }

    private void close(String sessionId, CloseStatus status) {
        WebSocketSession session = sessions.get(sessionId);

        if (session == null) {
            return;
        }

        try {
            session.close(status);
        } catch (IOException alreadyGone) {
            log.debug("WebSocket session {} was already closing", sessionId, alreadyGone);
        }
    }

    void forget(String sessionId) {
        Connected session = connected.remove(sessionId);

        if (session != null) {
            session.expiry().cancel(false);
        }
    }

    private boolean isActive(UUID userId) {
        return userRepository.findAccountStateById(userId)
                .map(AccountState::status)
                .orElse(UserStatus.DELETED) == UserStatus.ACTIVE;
    }
}
