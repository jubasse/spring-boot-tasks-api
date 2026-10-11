package io.julienmetral.tasks.identity.entities;

import org.springframework.security.core.annotation.ExpressionTemplateValueProvider;

public enum UserRole implements ExpressionTemplateValueProvider {
    USER,
    ADMIN;

    @Override
    public String getExpressionTemplateValue() {
        return "'" + name() + "'";
    }
}
