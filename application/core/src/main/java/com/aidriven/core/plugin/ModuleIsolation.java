package com.aidriven.core.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation documenting a module's isolation boundaries.
 * Used for documentation and static analysis — not enforced at runtime.
 *
 * <p>Modules annotated with this declare their:
 * <ul>
 *   <li>{@link #dependencies()} — other modules this module depends on</li>
 *   <li>{@link #exports()} — packages this module exposes to dependents</li>
 * </ul>
 *
 * <p>This supports the microservices adaptation goal by making module
 * boundaries explicit and discoverable.</p>
 */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleIsolation {

    /** Module identifier (e.g., "core", "jira-client", "bitbucket-client"). */
    String module();

    /** Modules this module depends on. */
    String[] dependencies() default {};

    /** Packages exported to dependents. */
    String[] exports() default {};

    /** Brief description of the module's responsibility. */
    String description() default "";
}