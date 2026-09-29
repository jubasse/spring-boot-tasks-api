package io.julienmetral.tasks.realtime.controllers;

import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.realtime.exceptions.TooManyNotificationStreamsException;
import io.julienmetral.tasks.realtime.sse.NotificationStreams;
import io.julienmetral.tasks.support.WebLayerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.WebCallers.everyAccountIsActive;
import static io.julienmetral.tasks.support.WebCallers.withoutUid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class NotificationStreamControllerWebMvcTests {

    private static final String STREAM = "/api/v1/notifications/stream";

    private static final Instant TOKEN_EXPIRY = Instant.parse("2026-03-04T05:21:07Z");

    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationStreams notificationStreams;

    @Autowired
    private UserRepository userRepository;

    @Test
    void streamRequiresAuthentication() throws Exception {
        mockMvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(notificationStreams);
    }

    @Test
    void tokenWithoutUidIsForbidden() throws Exception {
        mockMvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).with(withoutUid()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userRepository, notificationStreams);
    }

    static Stream<Arguments> inactiveAccounts() {
        return Stream.of(
                Arguments.of("disabled", Optional.of(new AccountState(false, VERIFIED_AT))),
                Arguments.of("unverified", Optional.of(new AccountState(true, null))),
                Arguments.of("deleted", Optional.empty())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inactiveAccounts")
    void inactiveAccountIsForbiddenEvenWithAValidToken(String state, Optional<AccountState> account) throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.findAccountStateById(userId)).thenReturn(account);

        mockMvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).with(caller(userId)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationStreams);
    }

    @Test
    void activeCallerGetsAnAsynchronousEventStreamThatProxiesMustNotBuffer() throws Exception {
        everyAccountIsActive(userRepository);
        UUID userId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event().comment("open"));
        when(notificationStreams.open(userId, TOKEN_EXPIRY, null)).thenReturn(emitter);

        mockMvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).with(caller(userId)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(":open\n\n"));
    }

    @Test
    void streamLastsUntilTheExpiryOfTheCallersToken() throws Exception {
        everyAccountIsActive(userRepository);
        UUID userId = UUID.randomUUID();
        when(notificationStreams.open(any(), any(), isNull())).thenReturn(new SseEmitter());

        mockMvc.perform(get(STREAM).with(caller(userId)))
                .andExpect(request().asyncStarted());

        verify(notificationStreams).open(userId, TOKEN_EXPIRY, null);
    }

    @Test
    void lastEventIdHeaderIsHandedToTheStreams() throws Exception {
        everyAccountIsActive(userRepository);
        UUID userId = UUID.randomUUID();
        String lastEventId = UUID.randomUUID().toString();
        when(notificationStreams.open(userId, TOKEN_EXPIRY, lastEventId)).thenReturn(new SseEmitter());

        mockMvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).header("Last-Event-ID", lastEventId)
                        .with(caller(userId)))
                .andExpect(request().asyncStarted());

        verify(notificationStreams).open(userId, TOKEN_EXPIRY, lastEventId);
    }

    @Test
    void tooManyOpenStreamsIsAnUntypedTooManyRequestsProblem() throws Exception {
        everyAccountIsActive(userRepository);
        when(notificationStreams.open(any(), any(), any())).thenThrow(new TooManyNotificationStreamsException(5));

        mockMvc.perform(get(STREAM).accept(MediaType.TEXT_EVENT_STREAM).with(caller(UUID.randomUUID())))
                .andExpect(untypedProblem(429, "Too Many Requests"))
                .andExpect(jsonPath("$.detail").value("Too many open notification streams: at most 5 per user"));
    }

    private static JwtRequestPostProcessor caller(UUID userId) {
        return jwt()
                .jwt(token -> token.claim("uid", userId.toString()).expiresAt(TOKEN_EXPIRY))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
