package com.aidriven.jira;

import com.aidriven.core.service.SecretsService;
import com.aidriven.core.tracker.IssueTrackerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JiraClientTest {

    private final JiraClient client = new JiraClient("https://test.atlassian.net", "test@test.com", "token");

    @Test
    void should_parse_jira_date_format_with_plus_timezone() throws Exception {
        // Jira format: 2026-02-01T04:09:18.752+0000
        String jiraDate = "2026-02-01T04:09:18.752+0000";

        Instant result = invokeParseJiraDate(jiraDate);

        assertNotNull(result);
        assertEquals(2026, result.atZone(java.time.ZoneOffset.UTC).getYear());
        assertEquals(2, result.atZone(java.time.ZoneOffset.UTC).getMonthValue());
        assertEquals(1, result.atZone(java.time.ZoneOffset.UTC).getDayOfMonth());
    }

    @Test
    void should_parse_iso8601_date_format_with_z_suffix() throws Exception {
        // ISO-8601 format: 2026-02-01T04:09:18.752Z
        String isoDate = "2026-02-01T04:09:18.752Z";

        Instant result = invokeParseJiraDate(isoDate);

        assertNotNull(result);
        assertEquals(2026, result.atZone(java.time.ZoneOffset.UTC).getYear());
    }

    @Test
    void should_parse_jira_date_with_positive_offset() throws Exception {
        // Jira format with positive offset: 2026-02-01T12:00:00.000+0530
        String jiraDate = "2026-02-01T12:00:00.000+0530";

        Instant result = invokeParseJiraDate(jiraDate);

        assertNotNull(result);
        // 12:00 +0530 = 06:30 UTC
        assertEquals(6, result.atZone(java.time.ZoneOffset.UTC).getHour());
        assertEquals(30, result.atZone(java.time.ZoneOffset.UTC).getMinute());
    }

    @Test
    void should_parse_jira_date_with_negative_offset() throws Exception {
        // Jira format with negative offset: 2026-02-01T12:00:00.000-0500
        String jiraDate = "2026-02-01T12:00:00.000-0500";

        Instant result = invokeParseJiraDate(jiraDate);

        assertNotNull(result);
        // 12:00 -0500 = 17:00 UTC
        assertEquals(17, result.atZone(java.time.ZoneOffset.UTC).getHour());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void should_return_current_time_for_null_or_empty_date(String dateStr) throws Exception {
        Instant before = Instant.now();

        Instant result = invokeParseJiraDate(dateStr);

        Instant after = Instant.now();
        assertNotNull(result);
        assertTrue(result.isAfter(before.minusSeconds(1)) && result.isBefore(after.plusSeconds(1)));
    }

    @Test
    void should_return_current_time_for_invalid_date_format() throws Exception {
        String invalidDate = "not-a-date";
        Instant before = Instant.now();

        Instant result = invokeParseJiraDate(invalidDate);

        Instant after = Instant.now();
        assertNotNull(result);
        assertTrue(result.isAfter(before.minusSeconds(1)) && result.isBefore(after.plusSeconds(1)));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-01-15T10:30:00.000+0000, 2026, 1, 15, 10, 30",
            "2026-12-31T23:59:59.999+0000, 2026, 12, 31, 23, 59",
            "2026-06-15T00:00:00.000+0000, 2026, 6, 15, 0, 0"
    })
    void should_parse_various_jira_dates(String dateStr, int year, int month, int day, int hour, int minute)
            throws Exception {
        Instant result = invokeParseJiraDate(dateStr);

        var zdt = result.atZone(java.time.ZoneOffset.UTC);
        assertEquals(year, zdt.getYear());
        assertEquals(month, zdt.getMonthValue());
        assertEquals(day, zdt.getDayOfMonth());
        assertEquals(hour, zdt.getHour());
        assertEquals(minute, zdt.getMinute());
    }

    @Test
    void should_initialize_from_secrets() {
        SecretsService secretsService = mock(SecretsService.class);
        String secretArn = "test-arn";
        Map<String, Object> secretJson = Map.of(
                "baseUrl", "https://test.atlassian.net",
                "apiToken", "test-token",
                "email", "test@email.com");

        when(secretsService.getSecretJson(secretArn)).thenReturn(secretJson);

        JiraClient client = JiraClient.fromSecrets(secretsService, secretArn);

        assertNotNull(client);
        // Verify internal state via reflection if needed, but the successful return is
        // enough for initialization test
    }

    @Test
    void should_throw_when_secrets_missing_required_key() {
        SecretsService secretsService = mock(SecretsService.class);
        String secretArn = "test-arn";
        Map<String, Object> incompleteJson = Map.of(
                "baseUrl", "https://test.atlassian.net",
                "apiToken", "test-token"
        // email missing
        );

        when(secretsService.getSecretJson(secretArn)).thenReturn(incompleteJson);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> JiraClient.fromSecrets(secretsService, secretArn));

        assertTrue(ex.getCause().getMessage().contains("Missing required key 'email'"));
    }

    @Test
    void should_extract_project_key_from_valid_ticket_key() throws Exception {
        // Given: A standard ticket key like "PROJ-123"
        String ticketKey = "PROJ-123";

        // When: extractProjectKey is invoked
        String result = invokeExtractProjectKey(ticketKey);

        // Then: Returns "PROJ"
        assertEquals("PROJ", result);
    }

    @Test
    void should_return_key_as_is_when_no_hyphen_present() throws Exception {
        // Given: A malformed key without a hyphen
        String ticketKey = "NOHYPHEN";

        // When: extractProjectKey is invoked
        String result = invokeExtractProjectKey(ticketKey);

        // Then: Returns the key itself
        assertEquals("NOHYPHEN", result);
    }

    @Test
    void should_return_empty_string_when_ticket_key_is_null() throws Exception {
        // When: extractProjectKey is invoked with null
        String result = invokeExtractProjectKey(null);

        // Then: Returns empty string
        assertEquals("", result);
    }

    @Test
    void should_handle_ticket_key_with_multiple_hyphens() throws Exception {
        // Given: A key with multiple hyphens like "MY-PROJ-123"
        String ticketKey = "MY-PROJ-123";

        // When: extractProjectKey is invoked
        String result = invokeExtractProjectKey(ticketKey);

        // Then: Returns everything before the first hyphen
        assertEquals("MY", result);
    }

    @Test
    void should_handle_empty_ticket_key() throws Exception {
        // Given: An empty string
        String ticketKey = "";

        // When: extractProjectKey is invoked
        String result = invokeExtractProjectKey(ticketKey);

        // Then: Returns empty string since no hyphen
        assertEquals("", result);
    }

    /**
     * Helper method to invoke the private parseJiraDate method via reflection.
     */
    private Instant invokeParseJiraDate(String dateStr) throws Exception {
        Method method = JiraClient.class.getDeclaredMethod("parseJiraDate", String.class);
        method.setAccessible(true);
        return (Instant) method.invoke(client, dateStr);
    }

    /**
     * Helper method to invoke the private extractProjectKey method via reflection.
     */
    private String invokeExtractProjectKey(String ticketKey) throws Exception {
        Method method = JiraClient.class.getDeclaredMethod("extractProjectKey", String.class);
        method.setAccessible(true);
        return (String) method.invoke(client, ticketKey);
    }

    @Test
    void should_implement_issueTrackerClient() {
        assertTrue(client instanceof IssueTrackerClient,
                "JiraClient should implement IssueTrackerClient");
    }
}
