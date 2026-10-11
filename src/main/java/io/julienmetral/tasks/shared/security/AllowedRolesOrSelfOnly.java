package io.julienmetral.tasks.shared.security;

import io.julienmetral.tasks.identity.entities.UserRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@AccessDescription("The user themselves, or the {value} role.")
@PreAuthorize(
        """
        hasAnyRole({value})
        or @userAuthorization.currentUserIsSelf(#id, authentication)
        """)
public @interface AllowedRolesOrSelfOnly {

    UserRole[] value();
}
