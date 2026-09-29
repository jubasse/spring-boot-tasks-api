package io.julienmetral.tasks.realtime.stomp;

import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompSessionsTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Instant EXPIRES_AT = Instant.parse("2026-03-04T05:15:00Z");

    private static final CloseStatus TOKEN_EXPIRED = new CloseStatus(1008, "Access token expired");

    private static final CloseStatus ACCOUNT_NOT_ACTIVE = new CloseStatus(1008, "Account not active");

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskScheduler scheduler;

    @Mock
    private ScheduledFuture<Object> expiry;

    @Mock
    private WebSocketHandler handler;

    private StompSessions sessions;

    private WebSocketHandler tracking;

    @BeforeEach
    void createSessions() {
        sessions = new StompSessions(userRepository, scheduler);
        tracking = sessions.track(handler);
    }

    @Test
    void trackedConnectionIsHandedOnToTheHandler() throws Exception {
        WebSocketSession session = open("s1");

        tracking.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(handler).afterConnectionEstablished(session);
        verify(handler).afterConnectionClosed(session, CloseStatus.NORMAL);
    }

    @Test
    void connectedSessionIsClosedAsAPolicyViolationWhenItsTokenExpires() throws Exception {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));

        sessions.connected("s1", USER_ID, EXPIRES_AT);
        scheduledExpiry().run();

        verify(session).close(TOKEN_EXPIRED);
    }

    @Test
    void closedConnectionCancelsItsExpiry() throws Exception {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        sessions.connected("s1", USER_ID, EXPIRES_AT);

        tracking.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(expiry).cancel(false);
    }

    @Test
    void expiryOfAConnectionAlreadyClosedClosesNothing() throws Exception {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        sessions.connected("s1", USER_ID, EXPIRES_AT);
        tracking.afterConnectionClosed(session, CloseStatus.NORMAL);

        scheduledExpiry().run();

        verify(session, never()).close(any());
    }

    @Test
    void secondConnectOnTheSameConnectionReplacesItsExpiry() {
        open("s1");
        ScheduledFuture<?> laterExpiry = mock(ScheduledFuture.class);
        Instant later = EXPIRES_AT.plusSeconds(600);
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        doReturn(laterExpiry).when(scheduler).schedule(any(Runnable.class), eq(later));

        sessions.connected("s1", USER_ID, EXPIRES_AT);
        sessions.connected("s1", USER_ID, later);

        verify(expiry).cancel(false);
        verify(laterExpiry, never()).cancel(anyBoolean());
    }

    @Test
    void accountNoLongerActiveClosesEveryConnectionOfItsOwnAndNoOther() throws Exception {
        WebSocketSession first = open("s1");
        WebSocketSession second = open("s2");
        WebSocketSession otherUsers = open("s3");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        sessions.connected("s1", USER_ID, EXPIRES_AT);
        sessions.connected("s2", USER_ID, EXPIRES_AT);
        sessions.connected("s3", OTHER_USER_ID, EXPIRES_AT);
        when(userRepository.findAccountStateById(USER_ID))
                .thenReturn(Optional.of(new AccountState(false, EXPIRES_AT)));

        sessions.closeIfNoLongerActive(USER_ID);

        verify(first).close(ACCOUNT_NOT_ACTIVE);
        verify(second).close(ACCOUNT_NOT_ACTIVE);
        verify(otherUsers, never()).close(any());
        verify(userRepository, never()).findAccountStateById(OTHER_USER_ID);
    }

    @Test
    void accountStillActiveKeepsItsConnections() throws Exception {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        sessions.connected("s1", USER_ID, EXPIRES_AT);
        when(userRepository.findAccountStateById(USER_ID))
                .thenReturn(Optional.of(new AccountState(true, EXPIRES_AT)));

        sessions.closeIfNoLongerActive(USER_ID);

        verify(session, never()).close(any());
    }

    @Test
    void unverifiedAccountLosesItsConnections() throws Exception {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        sessions.connected("s1", USER_ID, EXPIRES_AT);
        when(userRepository.findAccountStateById(USER_ID)).thenReturn(Optional.of(new AccountState(true, null)));

        sessions.closeIfNoLongerActive(USER_ID);

        verify(session).close(ACCOUNT_NOT_ACTIVE);
    }

    @Test
    void deletedAccountLosesItsConnections() throws Exception {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        sessions.connected("s1", USER_ID, EXPIRES_AT);
        when(userRepository.findAccountStateById(USER_ID)).thenReturn(Optional.empty());

        sessions.closeIfNoLongerActive(USER_ID);

        verify(session).close(ACCOUNT_NOT_ACTIVE);
    }

    @Test
    void accountWithoutConnectionsHereIsNotRead() throws Exception {
        WebSocketSession notYetConnected = open("s1");

        sessions.closeIfNoLongerActive(USER_ID);

        verifyNoInteractions(userRepository);
        verify(notYetConnected, never()).close(any());
    }

    @Test
    void accountOfAConnectionAlreadyClosedIsNotRead() {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        sessions.connected("s1", USER_ID, EXPIRES_AT);
        closeQuietly(session);

        sessions.closeIfNoLongerActive(USER_ID);

        verifyNoInteractions(userRepository);
    }

    @Test
    void connectionAlreadyClosingIsLeftAlone() throws Exception {
        WebSocketSession session = open("s1");
        doReturn(expiry).when(scheduler).schedule(any(Runnable.class), eq(EXPIRES_AT));
        sessions.connected("s1", USER_ID, EXPIRES_AT);
        doThrow(new IOException("Broken pipe")).when(session).close(TOKEN_EXPIRED);

        assertThatNoException().isThrownBy(() -> scheduledExpiry().run());
    }

    private WebSocketSession open(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);

        try {
            tracking.afterConnectionEstablished(session);
        } catch (Exception unexpected) {
            throw new IllegalStateException(unexpected);
        }

        return session;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            tracking.afterConnectionClosed(session, CloseStatus.NORMAL);
        } catch (Exception unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private Runnable scheduledExpiry() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(EXPIRES_AT));

        return task.getValue();
    }
}
