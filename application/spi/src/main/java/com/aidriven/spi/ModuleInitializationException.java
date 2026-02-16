package com.aidriven.spi;

/**
 * Thrown when a {@link ServiceModule} fails to initialize.
 */
public class ModuleInitializationException extends Exception {

    private final String moduleId;

    public ModuleInitializationException(String moduleId, String message) {
        super(String.format("Module '%s' initialization failed: %s", moduleId, message));
        this.moduleId = moduleId;
    }

    public ModuleInitializationException(String moduleId, String message, Throwable cause) {
        super(String.format("Module '%s' initialization failed: %s", moduleId, message), cause);
        this.moduleId = moduleId;
    }

    public String getModuleId() {
        return moduleId;
    }
}
