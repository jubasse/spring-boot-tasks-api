package io.julienmetral.tasks.shared.security;

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
@AccessDescription("Requires the {adminRole} role.")
@PreAuthorize("hasRole({adminRole})")
public @interface AdminOnly {
    UserRole adminRole() default UserRole.ADMIN;
}