package io.julienmetral.tasks.notification.services;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.repositories.UserProfileRepository;
import io.julienmetral.tasks.task.services.CommentMentions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** A comment body as notifications show it: mentions rendered with display names, and cut after 1,000 characters. */
@Component
@RequiredArgsConstructor
public class CommentExcerpts {

    private static final int MAX_LENGTH = 1_000;

    private final UserProfileRepository userProfileRepository;

    public String of(String body) {
        Map<UUID, String> names = userProfileRepository
                .findAllById(CommentMentions.parse(body))
                .stream()
                .collect(Collectors.toMap(UserProfile::getId, UserProfile::getDisplayName));
        String rendered = CommentMentions.render(body, names::get);

        return rendered.length() <= MAX_LENGTH ? rendered : rendered.substring(0, MAX_LENGTH) + "...";
    }
}
