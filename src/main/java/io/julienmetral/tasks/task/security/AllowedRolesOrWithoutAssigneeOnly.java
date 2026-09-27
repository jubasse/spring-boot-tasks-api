package io.julienmetral.tasks.task.security;

import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.shared.security.AccessDescription;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Anyone may create an unassigned task; assigning it on creation needs one of these roles. Reads {@code #dto}. */
@Target({
        ElementType.METHOD,
        ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@AccessDescription("Any active user; assigning the task on creation requires the {value} role.")
@PreAuthorize("hasAnyRole({value}) or #dto.assignedTo() == null")
public @interface AllowedRolesOrWithoutAssigneeOnly {

    UserRole[] value();
}
