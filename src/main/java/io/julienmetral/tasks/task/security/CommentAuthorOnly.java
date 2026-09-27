package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.shared.security.AccessDescription;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * No role bypass: an admin can delete a comment but never put words in its author's mouth. The annotated method must
 * name the comment id parameter {@code commentId}.
 */
@Target({
        ElementType.METHOD,
        ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@AccessDescription("Only the comment's author.")
@PreAuthorize("@taskCommentAuthorization.currentUserWrote(#commentId, authentication)")
public @interface CommentAuthorOnly {
}
