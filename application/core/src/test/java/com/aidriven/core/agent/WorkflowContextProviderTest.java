package com.aidriven.core.agent;

import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowContextProviderTest {

    @Mock
    private TicketStateRepository ticketStateRepository;

    private WorkflowContextProvider provider;

    @BeforeEach
    void setUp() {
        provider = new WorkflowContextProvider(ticketStateRepository);
    }

    @Test
    void getContext_returnsContext_whenPrUrlExists() {
        // Given
        TicketState state = TicketState.forTicket("tenant1", "12345", "PROJ-123", ProcessingStatus.DONE)
                .withPrDetails("https://github.com/owner/repo/pull/42", "ai/PROJ-123-feature");
        state.setUpdatedAt(Instant.now());

        when(ticketStateRepository.getLatestState("tenant1", "12345"))
                .thenReturn(Optional.of(state));

        // When
        Optional<WorkflowContextProvider.WorkflowContext> result = provider.getContext("tenant1", "12345");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().prUrl()).isEqualTo("https://github.com/owner/repo/pull/42");
        assertThat(result.get().branchName()).isEqualTo("ai/PROJ-123-feature");
        assertThat(result.get().status()).isEqualTo("DONE");
    }

    @Test
    void getContext_returnsEmpty_whenNoPrUrl() {
        // Given
        TicketState state = TicketState.forTicket("tenant1", "12345", "PROJ-123", ProcessingStatus.RECEIVED);
        when(ticketStateRepository.getLatestState("tenant1", "12345"))
                .thenReturn(Optional.of(state));

        // When
        Optional<WorkflowContextProvider.WorkflowContext> result = provider.getContext("tenant1", "12345");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void getContext_returnsEmpty_whenNoState() {
        // Given
        when(ticketStateRepository.getLatestState(anyString(), anyString()))
                .thenReturn(Optional.empty());

        // When
        Optional<WorkflowContextProvider.WorkflowContext> result = provider.getContext("tenant1", "12345");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void getContext_returnsEmpty_whenRepositoryThrows() {
        // Given
        when(ticketStateRepository.getLatestState(anyString(), anyString()))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When
        Optional<WorkflowContextProvider.WorkflowContext> result = provider.getContext("tenant1", "12345");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void workflowContext_toPromptSection_formatsCorrectly() {
        // Given
        WorkflowContextProvider.WorkflowContext context = new WorkflowContextProvider.WorkflowContext(
                "https://github.com/owner/repo/pull/42",
                "ai/PROJ-123-feature",
                "COMPLETED",
                Instant.now()
        );

        // When
        String prompt = context.toPromptSection();

        // Then
        assertThat(prompt).contains("## Prior Automated Work");
        assertThat(prompt).contains("https://github.com/owner/repo/pull/42");
        assertThat(prompt).contains("ai/PROJ-123-feature");
        assertThat(prompt).contains("COMPLETED");
    }

    @Test
    void workflowContext_toPromptSection_handlesNullBranch() {
        // Given
        WorkflowContextProvider.WorkflowContext context = new WorkflowContextProvider.WorkflowContext(
                "https://github.com/owner/repo/pull/42",
                null,
                "COMPLETED",
                Instant.now()
        );

        // When
        String prompt = context.toPromptSection();

        // Then
        assertThat(prompt).contains("https://github.com/owner/repo/pull/42");
        assertThat(prompt).doesNotContain("**Branch:**");
    }
}
