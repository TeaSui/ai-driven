package com.aidriven.core.security;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates and sanitizes text inputs (like Jira ticket descriptions) to
 * prevent
 * prompt injection attacks and excessive token consumption.
 */
@Slf4j
public class InputSanitizer {

    private static final int MAX_INPUT_LENGTH = 100_000;

    // Common prompt injection attack patterns
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore previous instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ignore all previous instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard previous instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget previous instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you are now", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<system>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system:", Pattern.CASE_INSENSITIVE));

    /**
     * Sanitizes raw text from an external source (like Jira).
     *
     * @param input Raw text
     * @return Sanitized text safe for Claude ingestion
     */
    public static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        // 1. Truncate excessively long inputs
        if (input.length() > MAX_INPUT_LENGTH) {
            log.warn("Input truncated from {} to {}", input.length(), MAX_INPUT_LENGTH);
            input = input.substring(0, MAX_INPUT_LENGTH);
        }

        // 2. Strip dangerous HTML/Script tags (allows basic formatting like <b>, <i>,
        // <p>, <a>)
        // Note: Jira descriptions are usually parsed to ADF before we get them, but we
        // still do this just in case.
        Safelist safelist = Safelist.basic()
                .preserveRelativeLinks(true)
                .addAttributes("a", "href", "target")
                .removeEnforcedAttribute("a", "rel")
                .removeProtocols("a", "href", "ftp", "mailto"); // Allow relative and standard http/s

        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        String cleanHtml = Jsoup.clean(input, "", safelist, settings);

        // 3. Detect and neutralize structural Prompt Injection tags
        String neutralized = cleanHtml;
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(neutralized).find()) {
                log.warn("Potential prompt injection detected: matching pattern '{}'", pattern.pattern());
                // Neutralize by replacing the exact match with a placeholder
                neutralized = pattern.matcher(neutralized).replaceAll("[REDACTED INJECTION ATTEMPT]");
            }
        }

        return neutralized;
    }
}
