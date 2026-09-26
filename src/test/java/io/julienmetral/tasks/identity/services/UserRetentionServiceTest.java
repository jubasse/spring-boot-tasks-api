package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.config.UserRetentionProperties;
import io.julienmetral.tasks.identity.mail.InactiveAccountWarned;
import io.julienmetral.tasks.identity.repositories.UserRetentionQueries;
import io.julienmetral.tasks.identity.repositories.UserRetentionQueries.InactiveUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2030-06-15T04:00:00Z");

    // Three different durations, so that a cutoff computed from the wrong one is caught
    private static final Duration ANONYMIZE_AFTER = Duration.ofDays(30);
    private static final Duration INACTIVITY_PERIOD = Duration.ofDays(730);
    private static final Duration DELETION_NOTICE = Duration.ofDays(21);

    @Mock
    private UserRetentionQueries queries;

    @Mock
    private UserService userService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserRetentionService service;

    @BeforeEach
    void setUp() {
        service = new UserRetentionService(
                queries,
                userService,
                new UserRetentionProperties(true, "0 0 4 * * *", ANONYMIZE_AFTER, INACTIVITY_PERIOD, DELETION_NOTICE),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void runIsSkippedWhenAnotherInstanceHoldsTheLock() {
        when(queries.tryLock()).thenReturn(false);

        UserRetentionReport report = service.apply();

        assertThat(report).isEqualTo(UserRetentionReport.skippedRun());
        verify(queries).tryLock();
        verifyNoMoreInteractions(queries);
        verifyNoInteractions(userService, eventPublisher);
    }

    @Test
    void cutoffsAreComputedFromTheClockAndTheProperties() {
        when(queries.tryLock()).thenReturn(true);

        service.apply();

        verify(queries).anonymizeUsersDeletedBefore(Instant.parse("2030-05-16T04:00:00Z"), NOW);
        verify(queries).usersWarnedBefore(Instant.parse("2030-05-25T04:00:00Z"));
        verify(queries).warnUsersInactiveSince(Instant.parse("2028-06-15T04:00:00Z"), NOW);
    }

    @Test
    void lockIsTakenBeforeAnyWorkAndDeletedAccountsAreAnonymizedOnlyByALaterRun() {
        UUID expired = UUID.randomUUID();
        when(queries.tryLock()).thenReturn(true);
        when(queries.usersWarnedBefore(any())).thenReturn(List.of(expired));

        service.apply();

        InOrder order = inOrder(queries, userService);
        order.verify(queries).tryLock();
        order.verify(queries).anonymizeUsersDeletedBefore(any(), any());
        order.verify(queries).usersWarnedBefore(any());
        order.verify(userService).delete(expired);
        order.verify(queries).warnUsersInactiveSince(any(), any());
    }

    @Test
    void expiredAccountsAreDeletedThroughTheUserService() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(queries.tryLock()).thenReturn(true);
        when(queries.usersWarnedBefore(any())).thenReturn(List.of(first, second));

        service.apply();

        verify(userService).delete(first);
        verify(userService).delete(second);
        verifyNoMoreInteractions(userService);
    }

    @Test
    void eachWarnedUserGetsOneWarningAnnouncingTheDeletionDate() {
        when(queries.tryLock()).thenReturn(true);
        when(queries.warnUsersInactiveSince(any(), any())).thenReturn(List.of(
                new InactiveUser(UUID.randomUUID(), "jane@example.com", "Jane Doe", true),
                new InactiveUser(UUID.randomUUID(), "john@example.com", "John Roe", true)
        ));

        service.apply();

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        Instant deletionAt = Instant.parse("2030-07-06T04:00:00Z");
        assertThat(events.getAllValues()).containsExactly(
                new InactiveAccountWarned("jane@example.com", "Jane Doe", deletionAt),
                new InactiveAccountWarned("john@example.com", "John Roe", deletionAt)
        );
    }

    @Test
    void disabledAccountIsWarnedWithoutAnEmail() {
        when(queries.tryLock()).thenReturn(true);
        when(queries.warnUsersInactiveSince(any(), any())).thenReturn(List.of(
                new InactiveUser(UUID.randomUUID(), "disabled@example.com", "Disabled", false),
                new InactiveUser(UUID.randomUUID(), "jane@example.com", "Jane Doe", true)
        ));

        UserRetentionReport report = service.apply();

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue())
                .isEqualTo(new InactiveAccountWarned("jane@example.com", "Jane Doe", Instant.parse("2030-07-06T04:00:00Z")));
        assertThat(report.warned()).isEqualTo(2);
    }

    @Test
    void reportCountsAnonymizedWarnedAndDeletedAccounts() {
        when(queries.tryLock()).thenReturn(true);
        when(queries.anonymizeUsersDeletedBefore(any(), any()))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        when(queries.usersWarnedBefore(any())).thenReturn(List.of(UUID.randomUUID()));
        when(queries.warnUsersInactiveSince(any(), any())).thenReturn(List.of(
                new InactiveUser(UUID.randomUUID(), "a@example.com", "A", true),
                new InactiveUser(UUID.randomUUID(), "b@example.com", "B", true)
        ));

        UserRetentionReport report = service.apply();

        assertThat(report).isEqualTo(new UserRetentionReport(false, 3, 2, 1));
    }

    @Test
    void runWithNothingToDoDeletesNothingAndSendsNothing() {
        when(queries.tryLock()).thenReturn(true);

        UserRetentionReport report = service.apply();

        assertThat(report).isEqualTo(new UserRetentionReport(false, 0, 0, 0));
        verify(userService, never()).delete(any());
        verifyNoInteractions(eventPublisher);
    }
}
