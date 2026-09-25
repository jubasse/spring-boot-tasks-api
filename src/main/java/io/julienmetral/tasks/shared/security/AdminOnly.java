package io.julienmetral.tasks.shared.security;

import io.julienmetral.tasks.identity.entities.UserRole;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.*;

@Target({
        ElementType.METHOD,
        ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole({adminRole})")
public @interface AdminOnly {
    UserRole adminRole() default UserRole.ADMIN;
}