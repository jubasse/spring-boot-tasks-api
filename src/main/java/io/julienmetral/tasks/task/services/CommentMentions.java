package io.julienmetral.tasks.task.services;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mentions are written {@code <@user-id>} in a comment body. Clients insert the token from a user picker and render
 * it with the comment's {@code mentions}; an id is stable where a display name is neither unique nor permanent.
 */
public final class CommentMentions {

    private static final Pattern TOKEN = Pattern.compile(
            "<@([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})>"
    );

    private CommentMentions() {
    }

    /** The mentioned user ids, in order of first appearance. */
    public static Set<UUID> parse(String body) {
        Set<UUID> ids = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(body);

        while (matcher.find()) {
            ids.add(UUID.fromString(matcher.group(1)));
        }

        return Collections.unmodifiableSet(ids);
    }

    /** Replaces each token with {@code @name}; a token whose name is null stays as written. */
    public static String render(String body, Function<UUID, String> displayName) {
        return TOKEN.matcher(body).replaceAll(match -> {
            String name = displayName.apply(UUID.fromString(match.group(1)));

            return Matcher.quoteReplacement(name == null ? match.group() : "@" + name);
        });
    }
}
