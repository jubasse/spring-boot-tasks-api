package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.entities.UserRole;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({
        ElementType.METHOD,
        ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("""
        hasAnyRole({value})
        or @taskAuthorization.currentUserIsAssignedTo(
            #id,
            authentication
        )
        """)
public @interface AllowedRolesOrAssignedToOnly {

    UserRole[] value();
}