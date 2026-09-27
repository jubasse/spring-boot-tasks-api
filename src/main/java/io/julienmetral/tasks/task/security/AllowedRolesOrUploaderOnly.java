package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.shared.security.AccessDescription;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The annotated method must name the attachment id parameter {@code attachmentId}. */
@Target({
        ElementType.METHOD,
        ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@AccessDescription("The attachment's uploader, or the {value} role.")
@PreAuthorize("""
        hasAnyRole({value})
        or @taskAttachmentAuthorization.currentUserUploaded(
            #attachmentId,
            authentication
        )
        """)
public @interface AllowedRolesOrUploaderOnly {

    UserRole[] value();
}
