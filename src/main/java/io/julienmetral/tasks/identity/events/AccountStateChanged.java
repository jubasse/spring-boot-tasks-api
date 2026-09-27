package io.julienmetral.tasks.identity.events;

import java.util.UUID;

/**
 * An account was enabled, disabled, verified or deleted. Every such change publishes it, native SQL included: it is
 * what evicts the account's cached status (see {@code UserStatusLookup}).
 */
public record AccountStateChanged(UUID userId) {
}
