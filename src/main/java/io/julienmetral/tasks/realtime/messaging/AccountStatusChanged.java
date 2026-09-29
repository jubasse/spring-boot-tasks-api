package io.julienmetral.tasks.realtime.messaging;

import java.util.UUID;

/** Broadcast when an account changes, so every instance evicts its cached status and closes streams it no longer allows. */
public record AccountStatusChanged(UUID userId) {
}
