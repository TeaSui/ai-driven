package com.aidriven.core.source;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryResolverTest {

    @Test
    void should_resolve_from_valid_label() {
        List<String> labels = List.of("repo:TeaSui/ai-driven");
        RepositoryResolver.ResolvedRepository result = RepositoryResolver.resolve(
                labels, null, "DefaultOwner", "DefaultRepo", "GITHUB");

        assertNotNull(result);
        assertEquals("TeaSui", result.owner());
        assertEquals("ai-driven", result.repo());
    }

    @Test
    void should_resolve_from_label_with_spaces_and_casing() {
        List<String> labels = List.of(" REPO:TeaKuo/test-repo ");
        RepositoryResolver.ResolvedRepository result = RepositoryResolver.resolve(
                labels, null, "DefaultOwner", "DefaultRepo", "GITHUB");

        assertNotNull(result);
        assertEquals("TeaKuo", result.owner());
        assertEquals("test-repo", result.repo());
    }

    @Test
    void should_resolve_from_label_with_extra_colon() {
        List<String> labels = List.of("repo: TeaSui/ai-driven");
        RepositoryResolver.ResolvedRepository result = RepositoryResolver.resolve(
                labels, null, "DefaultOwner", "DefaultRepo", "GITHUB");

        assertNotNull(result);
        assertEquals("TeaSui", result.owner());
        assertEquals("ai-driven", result.repo());
    }

    @Test
    void should_fallback_to_default_when_no_matching_label() {
        List<String> labels = List.of("other-label");
        RepositoryResolver.ResolvedRepository result = RepositoryResolver.resolve(
                labels, null, "DefaultOwner", "DefaultRepo", "GITHUB");

        assertNotNull(result);
        assertEquals("DefaultOwner", result.owner());
        assertEquals("DefaultRepo", result.repo());
    }
}
