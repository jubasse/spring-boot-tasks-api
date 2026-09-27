package io.julienmetral.tasks.shared.openapi;

import io.julienmetral.tasks.shared.exceptions.ProblemType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The typed problems an operation can answer, added to its documented responses with an example of each. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DocumentedProblems {

    ProblemType[] value();
}
