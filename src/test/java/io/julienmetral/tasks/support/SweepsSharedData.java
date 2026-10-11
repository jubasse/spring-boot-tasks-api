package io.julienmetral.tasks.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a test class that acts on rows it did not create in the shared containers: a statement or a
 * service that sweeps a whole table (purges, cleanups, reminders, retention, the outbox relay), a
 * clock moved far enough that such a sweep reaches every row, or a lock row other classes use.
 *
 * <p>CI runs the suite in two JVMs at once (the {@code ci} Maven profile): untagged classes in two
 * forks, then these classes alone, one after the other. Next to a class of the other fork, such a
 * class would remind, erase or publish that class's data while its test runs.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag(SweepsSharedData.TAG)
public @interface SweepsSharedData {

    String TAG = "sweeps-shared-data";
}
