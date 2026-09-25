package io.julienmetral.tasks.shared.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize(
        "@userAuthorization.currentUserIsSelf(#id, authentication)"
)
public @interface SelfOnly {
}