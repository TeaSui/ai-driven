/**
 * Contracts module — lightweight, dependency-free interfaces for the AI-Driven system.
 * <p>
 * This module defines the SPI (Service Provider Interface) contracts that
 * all integrations must implement. It has minimal dependencies (only Jackson
 * for JSON) so that third-party plugin JARs can depend on it without
 * pulling in AWS SDK, Spring, or other heavy frameworks.
 * </p>
 *
 * <h2>Package Structure</h2>
 * <ul>
 *   <li>{@code contracts.source} — Source control operations (Git platforms)</li>
 *   <li>{@code contracts.tracker} — Issue tracker operations (Jira, Linear, etc.)</li>
 *   <li>{@code contracts.ai} — AI model operations (Claude, OpenAI, etc.)</li>
 *   <li>{@code contracts.tool} — Tool provider SPI for agent mode</li>
 *   <li>{@code contracts.config} — Tenant configuration and resolution</li>
 *   <li>{@code contracts.plugin} — Plugin discovery and registration</li>
 * </ul>
 *
 * <h2>For Plugin Authors</h2>
 * <ol>
 *   <li>Add {@code contracts} as a compile-only dependency</li>
 *   <li>Implement {@link com.aidriven.contracts.plugin.PluginDescriptor}</li>
 *   <li>Create {@code META-INF/services/com.aidriven.contracts.plugin.PluginDescriptor}</li>
 *   <li>Register implementations in {@code initialize(PluginRegistry)}</li>
 * </ol>
 */
package com.aidriven.contracts;
