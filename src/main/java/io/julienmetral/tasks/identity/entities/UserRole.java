package io.julienmetral.tasks.identity.entities;

import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.annotation.ExpressionTemplateValueProvider;

public enum UserRole implements ExpressionTemplateValueProvider {
    USER,
    ADMIN;

    @Override
    @NullMarked
    public String getExpressionTemplateValue() {
        return "'" + name() + "'";
    }

}
