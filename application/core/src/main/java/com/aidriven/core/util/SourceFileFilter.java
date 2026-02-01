package com.aidriven.core.util;

import java.nio.file.Path;
import java.util.Set;

/**
 * Shared utility for filtering source files from repository archives.
 * Used by both BitbucketFetchHandler (linear workflow) and ContextService (legacy workflow).
 */
public final class SourceFileFilter {

    /** Directories to skip entirely during file walk. */
    public static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", ".gradle", ".mvn", ".idea", ".vscode",
            "__pycache__", ".pytest_cache", ".mypy_cache",
            "build", "target", "dist", "out", "bin", ".next",
            "vendor", ".terraform", ".serverless",
            "coverage", ".nyc_output", "htmlcov");

    /** Binary/non-source extensions to skip. */
    public static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".class", ".jar", ".war", ".ear", ".zip", ".tar", ".gz", ".bz2", ".7z",
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".webp",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".mp3", ".mp4", ".avi", ".mov", ".wav", ".flac",
            ".exe", ".dll", ".so", ".dylib", ".o", ".a",
            ".woff", ".woff2", ".ttf", ".eot",
            ".DS_Store", ".lock");

    /** Large generated files to skip. */
    public static final Set<String> EXCLUDED_FILENAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock",
            "Gemfile.lock", "Cargo.lock", "go.sum", "poetry.lock",
            "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd");

    /** Common config files that start with '.' but should be included. */
    private static final Set<String> COMMON_CONFIG_FILES = Set.of(
            ".gitignore", ".env.example", ".editorconfig", ".prettierrc",
            ".eslintrc", ".eslintrc.json", ".eslintrc.js", ".babelrc",
            ".dockerignore", ".flake8", ".pylintrc");

    private SourceFileFilter() {
        // Utility class
    }

    /**
     * Determines if a file should be included in the code context.
     * Excludes binary files, build outputs, dependencies, and lock files.
     */
    public static boolean isIncluded(Path file) {
        String fullPath = file.toString().replace('\\', '/');

        for (String excluded : EXCLUDED_DIRS) {
            if (fullPath.contains("/" + excluded + "/")) {
                return false;
            }
        }

        String fileName = file.getFileName().toString();

        if (EXCLUDED_FILENAMES.contains(fileName)) {
            return false;
        }

        String lower = fileName.toLowerCase();
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (lower.endsWith(ext.toLowerCase())) {
                return false;
            }
        }

        if (fileName.startsWith(".") && !COMMON_CONFIG_FILES.contains(fileName)) {
            return false;
        }

        return true;
    }

    /**
     * Quick binary content detection.
     * If more than 5% of the first 1000 chars are non-printable, treat as binary.
     */
    public static boolean isBinaryContent(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        int checkLength = Math.min(content.length(), 1000);
        int nonPrintable = 0;
        for (int i = 0; i < checkLength; i++) {
            char c = content.charAt(i);
            if (c != '\n' && c != '\r' && c != '\t' && (c < 32 || c == 127)) {
                nonPrintable++;
            }
        }
        return (double) nonPrintable / checkLength > 0.05;
    }

    /**
     * Truncates content to a maximum number of characters.
     */
    public static String truncate(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "\n... [truncated]";
    }
}
