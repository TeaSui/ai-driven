package com.aidriven.jira;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

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
    void should_parse_various_jira_dates(String dateStr, int year, int month, int day, int hour, int minute) throws Exception {
        Instant result = invokeParseJiraDate(dateStr);

        var zdt = result.atZone(java.time.ZoneOffset.UTC);
        assertEquals(year, zdt.getYear());
        assertEquals(month, zdt.getMonthValue());
        assertEquals(day, zdt.getDayOfMonth());
        assertEquals(hour, zdt.getHour());
        assertEquals(minute, zdt.getMinute());
    }

    /**
     * Helper method to invoke the private parseJiraDate method via reflection.
     */
    private Instant invokeParseJiraDate(String dateStr) throws Exception {
        Method method = JiraClient.class.getDeclaredMethod("parseJiraDate", String.class);
        method.setAccessible(true);
        return (Instant) method.invoke(client, dateStr);
    }
}
