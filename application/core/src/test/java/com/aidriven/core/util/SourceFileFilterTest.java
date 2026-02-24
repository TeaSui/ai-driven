package com.aidriven.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileFilterTest {

    // --- isIncluded ---

    @Test
    void should_include_java_source_file() {
        assertTrue(SourceFileFilter.isIncluded(Path.of("/repo/src/main/App.java")));
    }

    @Test
    void should_include_typescript_file() {
        assertTrue(SourceFileFilter.isIncluded(Path.of("/repo/src/index.ts")));
    }

    @Test
    void should_include_gitignore_as_config_file() {
        assertTrue(SourceFileFilter.isIncluded(Path.of("/repo/.gitignore")));
    }

    @Test
    void should_include_editorconfig() {
        assertTrue(SourceFileFilter.isIncluded(Path.of("/repo/.editorconfig")));
    }

    @ParameterizedTest
    @ValueSource(strings = { ".git", "node_modules", "build", "target", "dist", ".gradle", "__pycache__" })
    void should_exclude_build_and_dependency_directories(String dirName) {
        Path file = Path.of("/repo/" + dirName + "/some/File.java");

        assertFalse(SourceFileFilter.isIncluded(file));
    }

    @ParameterizedTest
    @ValueSource(strings = { ".class", ".jar", ".zip", ".png", ".jpg", ".pdf", ".exe", ".dll" })
    void should_exclude_binary_extensions(String ext) {
        Path file = Path.of("/repo/src/file" + ext);

        assertFalse(SourceFileFilter.isIncluded(file));
    }

    @ParameterizedTest
    @ValueSource(strings = { "package-lock.json", "yarn.lock", "gradlew", "gradlew.bat", "go.sum" })
    void should_exclude_lock_and_generated_files(String filename) {
        Path file = Path.of("/repo/" + filename);

        assertFalse(SourceFileFilter.isIncluded(file));
    }

    @Test
    void should_exclude_hidden_files_without_config_names() {
        assertFalse(SourceFileFilter.isIncluded(Path.of("/repo/.hidden_file")));
    }

    @Test
    void should_exclude_ds_store() {
        assertFalse(SourceFileFilter.isIncluded(Path.of("/repo/.DS_Store")));
    }

    @Test
    void should_exclude_build_directory_at_root_relative() {
        assertFalse(SourceFileFilter.isIncluded(Path.of("build/some/File.java")));
    }

    @Test
    void should_exclude_build_directory_nested_relative() {
        assertFalse(SourceFileFilter.isIncluded(Path.of("src/module/build/File.java")));
    }

    @Test
    void should_not_exclude_file_named_build_in_non_excluded_dir() {
        assertTrue(SourceFileFilter.isIncluded(Path.of("src/some/build.txt")));
    }

    @Test
    void should_not_exclude_dir_containing_build_in_name() {
        assertTrue(SourceFileFilter.isIncluded(Path.of("src/mybuild/File.java")));
    }

    @Test
    void should_handle_absolute_paths_robustly() {
        assertFalse(SourceFileFilter.isIncluded(Path.of("/opt/app/build/File.java")));
    }

    // --- isBinaryContent ---

    @Test
    void should_detect_text_content_as_non_binary() {
        assertFalse(SourceFileFilter.isBinaryContent("public class Main {}"));
    }

    @Test
    void should_detect_binary_content() {
        StringBuilder binary = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            binary.append((char) 0);
        }
        assertTrue(SourceFileFilter.isBinaryContent(binary.toString()));
    }

    @Test
    void should_handle_empty_content() {
        assertFalse(SourceFileFilter.isBinaryContent(""));
    }

    @Test
    void should_handle_null_content() {
        assertFalse(SourceFileFilter.isBinaryContent(null));
    }

    @Test
    void should_allow_newlines_tabs_carriage_returns() {
        String content = "line1\nline2\r\n\ttabbed";

        assertFalse(SourceFileFilter.isBinaryContent(content));
    }

    // --- truncate ---

    @Test
    void should_return_content_under_max_length() {
        String content = "short content";

        assertEquals(content, SourceFileFilter.truncate(content, 100));
    }

    @Test
    void should_truncate_content_over_max_length() {
        String content = "x".repeat(200);

        String result = SourceFileFilter.truncate(content, 50);

        assertEquals(50, result.indexOf("\n... [truncated]"));
        assertTrue(result.length() < 200);
    }

    @Test
    void should_handle_null_content_in_truncate() {
        assertEquals("", SourceFileFilter.truncate(null, 100));
    }

    @Test
    void should_handle_exact_max_length() {
        String content = "x".repeat(100);

        assertEquals(content, SourceFileFilter.truncate(content, 100));
    }
}
