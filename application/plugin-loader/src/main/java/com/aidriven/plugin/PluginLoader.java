package com.aidriven.plugin;

import com.aidriven.contracts.plugin.PluginDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Discovers and initializes plugins via Java's {@link ServiceLoader} mechanism.
 * <p>
 * On startup, scans the classpath for implementations of {@link PluginDescriptor}
 * registered in {@code META-INF/services/com.aidriven.contracts.plugin.PluginDescriptor}.
 * Each discovered plugin is initialized with the shared {@link DefaultPluginRegistry}.
 * </p>
 *
 * <p>
 * Usage:
 * <pre>{@code
 * DefaultPluginRegistry registry = new DefaultPluginRegistry();
 * PluginLoader loader = new PluginLoader(registry);
 * loader.loadPlugins();
 *
 * // Now use registry to look up implementations
 * registry.getSourceControlFactory("gitlab").ifPresent(factory -> ...);
 * }</pre>
 * </p>
 */
@Slf4j
public class PluginLoader {

    private final DefaultPluginRegistry registry;
    private final List<PluginDescriptor> loadedPlugins;

    public PluginLoader(DefaultPluginRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.loadedPlugins = new ArrayList<>();
    }

    /**
     * Discovers and initializes all plugins on the classpath.
     *
     * @return Number of plugins successfully loaded
     */
    public int loadPlugins() {
        log.info("Start loadPlugins");
        ServiceLoader<PluginDescriptor> serviceLoader = ServiceLoader.load(PluginDescriptor.class);

        int loaded = 0;
        for (PluginDescriptor plugin : serviceLoader) {
            try {
                log.info("Loading plugin: {} v{} (id={})",
                        plugin.name(), plugin.version(), plugin.id());

                plugin.initialize(registry);
                loadedPlugins.add(plugin);
                loaded++;

                log.info("Plugin loaded successfully: {} — capabilities: {}",
                        plugin.id(), plugin.capabilities());

            } catch (Exception e) {
                log.error("Failed to load plugin '{}': {}", plugin.id(), e.getMessage(), e);
                // Continue loading other plugins — one failure shouldn't block all
            }
        }

        log.info("Plugin loading complete: {} plugins loaded", loaded);
        return loaded;
    }

    /**
     * Shuts down all loaded plugins.
     */
    public void shutdown() {
        for (PluginDescriptor plugin : loadedPlugins) {
            try {
                plugin.shutdown();
                log.info("Plugin shut down: {}", plugin.id());
            } catch (Exception e) {
                log.warn("Error shutting down plugin '{}': {}", plugin.id(), e.getMessage());
            }
        }
        loadedPlugins.clear();
    }

    /**
     * Returns the list of loaded plugins (for diagnostics).
     */
    public List<PluginDescriptor> getLoadedPlugins() {
        return Collections.unmodifiableList(loadedPlugins);
    }

    /**
     * Returns the plugin registry.
     */
    public DefaultPluginRegistry getRegistry() {
        return registry;
    }
}
