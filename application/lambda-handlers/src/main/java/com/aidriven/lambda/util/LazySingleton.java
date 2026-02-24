package com.aidriven.lambda.util;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Thread-safe lazy singleton wrapper for expensive resources.
 * Provides efficient double-checked locking pattern for initialization.
 *
 * @param <T> the type of resource being managed
 */
public class LazySingleton<T> {

    private volatile T instance;
    private final Supplier<T> initializer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Creates a new lazy singleton with the given initializer.
     *
     * @param initializer a supplier that creates the resource
     */
    public LazySingleton(Supplier<T> initializer) {
        if (initializer == null) {
            throw new IllegalArgumentException("Initializer cannot be null");
        }
        this.initializer = initializer;
    }

    /**
     * Gets or initializes the singleton instance.
     * Uses double-checked locking for optimal performance.
     *
     * @return the singleton instance
     */
    public T get() {
        // Fast path: read lock if already initialized
        if (instance != null) {
            return instance;
        }

        // Slow path: write lock for initialization
        lock.writeLock().lock();
        try {
            if (instance == null) {
                instance = initializer.get();
                if (instance == null) {
                    throw new IllegalStateException("Initializer returned null");
                }
            }
            return instance;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Resets the singleton instance (mainly for testing).
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            instance = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns true if the instance has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return instance != null;
    }
}

