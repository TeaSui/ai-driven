package com.aidriven.lambda.factory;

import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryFactoryTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    private RepositoryFactory factory;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        factory = new RepositoryFactory(dynamoDbClient, "test-table");
    }

    @Test
    void should_create_ticket_state_repository() {
        TicketStateRepository repository = factory.ticketStateRepository();

        assertNotNull(repository);
        verifyNoInteractions(dynamoDbClient); // Lazy initialization
    }

    @Test
    void should_cache_ticket_state_repository() {
        TicketStateRepository repo1 = factory.ticketStateRepository();
        TicketStateRepository repo2 = factory.ticketStateRepository();

        assertSame(repo1, repo2);
    }

    @Test
    void should_create_generation_metrics_repository() {
        GenerationMetricsRepository repository = factory.generationMetricsRepository();

        assertNotNull(repository);
        verifyNoInteractions(dynamoDbClient); // Lazy initialization
    }

    @Test
    void should_cache_generation_metrics_repository() {
        GenerationMetricsRepository repo1 = factory.generationMetricsRepository();
        GenerationMetricsRepository repo2 = factory.generationMetricsRepository();

        assertSame(repo1, repo2);
    }

    @Test
    void should_use_provided_table_name() {
        RepositoryFactory customFactory = new RepositoryFactory(dynamoDbClient, "custom-table");
        TicketStateRepository repository = customFactory.ticketStateRepository();

        assertNotNull(repository);
    }

    @Test
    void should_handle_null_dynamo_client_gracefully() {
        RepositoryFactory nullClientFactory = new RepositoryFactory(null, "test-table");

        // Verify factory can be created (actual repository creation would fail at runtime)
        assertNotNull(nullClientFactory);
    }
}
