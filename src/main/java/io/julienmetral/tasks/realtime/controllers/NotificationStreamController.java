package io.julienmetral.tasks.realtime.controllers;

import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.realtime.sse.NotificationStreams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Tag(name = "Notifications", description = "The caller's task notifications, as they happen.")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationStreamController {

    private final NotificationStreams notificationStreams;
    private final CurrentUser currentUser;

    @Operation(
            summary = "Stream your task notifications as server-sent events",
            description = """
                    The events are the webhook notifications of the caller (`task.assigned`, `task.commented`...), \
                    whatever their email settings: each has an `id`, the event name, and as data the webhook \
                    payload, `{"type", "timestamp", "data"}`. A comment is sent after 20 seconds without events.

                    The stream ends when the access token expires, or after 15 minutes. Reconnect with a fresh \
                    token and the `Last-Event-ID` header: the events missed meanwhile follow, or a `resync` event \
                    when they are no longer kept, after which reload what you show. An event can come twice: \
                    ignore an id you already have. Browsers' `EventSource` cannot send the token: use a client \
                    built on `fetch`."""
    )
    @ApiResponse(
            responseCode = "200",
            description = "The stream, open until the token expires.",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string"))
    )
    @ApiResponse(
            responseCode = "429",
            description = "Too many streams are open for this account: close one first.",
            content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(ref = "#/components/schemas/Problem")
            )
    )
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            JwtAuthenticationToken authentication,
            HttpServletResponse response
    ) {
        UUID userId = currentUser.getId(authentication)
                .orElseThrow(() -> new AccessDeniedException("No user id in the access token"));

        // Stops a buffering reverse proxy (nginx) from holding the events back
        response.setHeader("X-Accel-Buffering", "no");

        return notificationStreams.open(userId, authentication.getToken().getExpiresAt(), lastEventId);
    }
}
