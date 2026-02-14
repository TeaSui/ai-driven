package com.aidriven.core.context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dynamically maps high-level business concepts (Frontend, Backend,
 * Infrastructure)
 * to actual directory paths in the project by inspecting the file tree.
 * <p>
 * This avoids hardcoding paths like "src/main/java" or "webapp", allowing the
 * system to adapt to different project structures automatically.
 */
public class DirectoryScanner {

    // Signals to identify component types
    private static final Map<String, List<String>> COMPONENT_SIGNALS = Map.of(
            "frontend", List.of("ui", "web", "static", "public", "assets", "views", "templates", "js", "ts", "css"),
            "backend", List.of("application", "core", "service", "controller", "model", "src/main/java", "app", "api"),
            "infrastructure", List.of("infrastructure", "infra", "cdk", "terraform", "k8s", "docker", "cloudformation"),
            "test", List.of("test", "tests", "spec", "e2e", "it", "junit"));

    /**
     * Scans the provided file tree and returns a map of concepts to matching paths.
     *
     * @param fileTree List of all file paths in the repository
     * @return Map of concept -> list of matching root directories (e.g., "backend"
     *         -> ["application/core"])
     */
    public static Map<String, List<String>> scan(List<String> fileTree) {
        Map<String, List<String>> conceptToPaths = new HashMap<>();

        // Extract all unique directory paths from the file tree
        Set<String> directories = fileTree.stream()
                .map(DirectoryScanner::getParentDir)
                .filter(d -> !d.isEmpty() && !d.equals("."))
                .collect(Collectors.toSet());

        for (Map.Entry<String, List<String>> entry : COMPONENT_SIGNALS.entrySet()) {
            String concept = entry.getKey();
            List<String> signals = entry.getValue();

            // Find directories that match any of the signals
            List<String> matchingDirs = directories.stream()
                    .filter(dir -> matchesSignal(dir, signals))
                    .collect(Collectors.toList());

            if (!matchingDirs.isEmpty()) {
                conceptToPaths.put(concept, matchingDirs);
            }
        }

        return conceptToPaths;
    }

    private static boolean matchesSignal(String dir, List<String> signals) {
        String lowerDir = dir.toLowerCase();
        // Check if the directory name itself contains a signal word
        // e.g., "application/core" matches "core"
        return signals.stream().anyMatch(lowerDir::contains);
    }

    private static String getParentDir(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash > 0 ? path.substring(0, lastSlash) : "";
    }
}
