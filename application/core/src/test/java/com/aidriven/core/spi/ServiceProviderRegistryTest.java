package com.aidriven.core.spi;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.tracker.IssueTrackerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ServiceProviderRegistryTest {

    private ServiceProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ServiceProviderRegistry();
    }

    @Nested
    class Registration {

        @Test
        void register_and_retrieve_by_qualifier() {
            SourceControlClient client = mock(SourceControlClient.class);
            registry.register(SourceControlClient.class, "bitbucket", client);

            SourceControlClient result = registry.get(SourceControlClient.class, "bitbucket");
            assertSame(client, result);
        }

        @Test
        void register_multiple_implementations_of_same_type() {
            SourceControlClient bb = mock(SourceControlClient.class);
            SourceControlClient gh = mock(SourceControlClient.class);

            registry.register(SourceControlClient.class, "bitbucket", bb);
            registry.register(SourceControlClient.class, "github", gh);

            assertSame(bb, registry.get(SourceControlClient.class, "bitbucket"));
            assertSame(gh, registry.get(SourceControlClient.class, "github"));
            assertEquals(2, registry.size());
        }

        @Test
        void register_replaces_existing_provider() {
            SourceControlClient old = mock(SourceControlClient.class);
            SourceControlClient replacement = mock(SourceControlClient.class);

            registry.register(SourceControlClient.class, "bb", old);
            registry.register(SourceControlClient.class, "bb", replacement);

            assertSame(replacement, registry.get(SourceControlClient.class, "bb"));
            assertEquals(1, registry.size());
        }

        @Test
        void register_throws_on_null_type() {
            assertThrows(NullPointerException.class,
                    () -> registry.register(null, "q", "instance"));
        }

        @Test
        void register_throws_on_null_qualifier() {
            assertThrows(NullPointerException.class,
                    () -> registry.register(SourceControlClient.class, null, mock(SourceControlClient.class)));
        }

        @Test
        void register_throws_on_null_instance() {
            assertThrows(NullPointerException.class,
                    () -> registry.register(SourceControlClient.class, "q", null));
        }

        @Test
        void qualifier_is_case_insensitive() {
            SourceControlClient client = mock(SourceControlClient.class);
            registry.register(SourceControlClient.class, "GitHub", client);

            assertSame(client, registry.get(SourceControlClient.class, "github"));
            assertSame(client, registry.get(SourceControlClient.class, "GITHUB"));
        }
    }

    @Nested
    class DefaultProvider {

        @Test
        void registerDefault_sets_default_qualifier() {
            SourceControlClient client = mock(SourceControlClient.class);
            registry.registerDefault(SourceControlClient.class, "bitbucket", client);

            assertSame(client, registry.getDefault(SourceControlClient.class));
        }

        @Test
        void getDefault_returns_sole_provider_when_no_default_set() {
            SourceControlClient client = mock(SourceControlClient.class);
            registry.register(SourceControlClient.class, "only-one", client);

            assertSame(client, registry.getDefault(SourceControlClient.class));
        }

        @Test
        void getDefault_throws_when_multiple_providers_and_no_default() {
            registry.register(SourceControlClient.class, "a", mock(SourceControlClient.class));
            registry.register(SourceControlClient.class, "b", mock(SourceControlClient.class));

            assertThrows(NoSuchElementException.class,
                    () -> registry.getDefault(SourceControlClient.class));
        }

        @Test
        void getDefault_throws_when_no_providers() {
            assertThrows(NoSuchElementException.class,
                    () -> registry.getDefault(SourceControlClient.class));
        }
    }

    @Nested
    class Lookup {

        @Test
        void get_throws_when_not_registered() {
            NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                    () -> registry.get(SourceControlClient.class, "nonexistent"));
            assertTrue(ex.getMessage().contains("SourceControlClient"));
            assertTrue(ex.getMessage().contains("nonexistent"));
        }

        @Test
        void find_returns_optional_when_present() {
            SourceControlClient client = mock(SourceControlClient.class);
            registry.register(SourceControlClient.class, "bb", client);

            assertTrue(registry.find(SourceControlClient.class, "bb").isPresent());
            assertSame(client, registry.find(SourceControlClient.class, "bb").get());
        }

        @Test
        void find_returns_empty_when_absent() {
            assertTrue(registry.find(SourceControlClient.class, "missing").isEmpty());
        }

        @Test
        void getAll_returns_all_providers_of_type() {
            SourceControlClient bb = mock(SourceControlClient.class);
            SourceControlClient gh = mock(SourceControlClient.class);
            IssueTrackerClient jira = mock(IssueTrackerClient.class);

            registry.register(SourceControlClient.class, "bb", bb);
            registry.register(SourceControlClient.class, "gh", gh);
            registry.register(IssueTrackerClient.class, "jira", jira);

            List<SourceControlClient> scClients = registry.getAll(SourceControlClient.class);
            assertEquals(2, scClients.size());

            List<IssueTrackerClient> itClients = registry.getAll(IssueTrackerClient.class);
            assertEquals(1, itClients.size());
        }

        @Test
        void getQualifiers_returns_all_qualifiers_for_type() {
            registry.register(SourceControlClient.class, "bitbucket", mock(SourceControlClient.class));
            registry.register(SourceControlClient.class, "github", mock(SourceControlClient.class));

            Set<String> qualifiers = registry.getQualifiers(SourceControlClient.class);
            assertEquals(Set.of("bitbucket", "github"), qualifiers);
        }

        @Test
        void isRegistered_returns_correct_status() {
            registry.register(SourceControlClient.class, "bb", mock(SourceControlClient.class));

            assertTrue(registry.isRegistered(SourceControlClient.class, "bb"));
            assertFalse(registry.isRegistered(SourceControlClient.class, "gh"));
        }
    }

    @Nested
    class Deregistration {

        @Test
        void deregister_removes_provider() {
            registry.register(SourceControlClient.class, "bb", mock(SourceControlClient.class));
            registry.deregister(SourceControlClient.class, "bb");

            assertFalse(registry.isRegistered(SourceControlClient.class, "bb"));
            assertEquals(0, registry.size());
        }

        @Test
        void deregister_nonexistent_is_noop() {
            assertDoesNotThrow(() -> registry.deregister(SourceControlClient.class, "missing"));
        }

        @Test
        void clear_removes_all_providers() {
            registry.register(SourceControlClient.class, "bb", mock(SourceControlClient.class));
            registry.register(IssueTrackerClient.class, "jira", mock(IssueTrackerClient.class));
            registry.setDefault(SourceControlClient.class, "bb");

            registry.clear();

            assertEquals(0, registry.size());
            assertThrows(NoSuchElementException.class,
                    () -> registry.getDefault(SourceControlClient.class));
        }
    }
}
