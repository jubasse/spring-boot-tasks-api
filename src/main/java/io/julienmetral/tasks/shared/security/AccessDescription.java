package io.julienmetral.tasks.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes, for the API documentation, who passes the {@code @PreAuthorize} rule of the annotation it marks.
 * {@code {attribute}} placeholders take that annotation's attribute values, such as its roles.
 * <p>
 * Warning: every security meta-annotation needs one; a test fails on an operation guarded by an undescribed rule.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccessDescription {

    String value();
}
