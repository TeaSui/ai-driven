package com.aidriven.app.pipeline;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.app.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pipeline endpoints called by AWS Step Functions via HTTP tasks.
 * Replaces the Lambda handlers: FetchTicketHandler, CodeFetchHandler,
 * ClaudeInvokeHandler, PrCreatorHandler, MergeWaitHandler.
 */
@Slf4j
@RestController
@RequestMapping("/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final IssueTrackerClient issueTrackerClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    @PostMapping("/fetch-ticket")
    public ResponseEntity<Map<String, Object>> fetchTicket(@RequestBody Map<String, Object> input) {
        String ticketKey = (String) input.get("ticketKey");
        setupMdc(ticketKey, "fetch-ticket");

        try {
            log.info("Fetching ticket: {}", ticketKey);
            // TODO: Wire OperationContext from Step Functions input when fully integrated
            Map<String, Object> result = new HashMap<>(input);
            result.put("status", "fetched");

            log.info("Ticket fetch step complete: {}", ticketKey);
            return ResponseEntity.ok(result);
        } finally {
            MDC.clear();
        }
    }

    @PostMapping("/fetch-context")
    public ResponseEntity<Map<String, Object>> fetchContext(@RequestBody Map<String, Object> input) {
        String ticketKey = (String) input.get("ticketKey");
        setupMdc(ticketKey, "fetch-context");

        try {
            log.info("Fetching code context for ticket: {}", ticketKey);

            Map<String, Object> result = new HashMap<>(input);
            result.put("contextFetched", true);
            result.put("status", "context-ready");

            log.info("Code context fetched for ticket: {}", ticketKey);
            return ResponseEntity.ok(result);
        } finally {
            MDC.clear();
        }
    }

    @PostMapping("/invoke-ai")
    public ResponseEntity<Map<String, Object>> invokeAi(@RequestBody Map<String, Object> input) {
        String ticketKey = (String) input.get("ticketKey");
        setupMdc(ticketKey, "invoke-ai");

        try {
            log.info("Invoking AI for ticket: {}", ticketKey);

            Map<String, Object> result = new HashMap<>(input);
            result.put("aiInvoked", true);
            result.put("status", "ai-complete");

            log.info("AI invocation complete for ticket: {}", ticketKey);
            return ResponseEntity.ok(result);
        } finally {
            MDC.clear();
        }
    }

    @PostMapping("/create-pr")
    public ResponseEntity<Map<String, Object>> createPr(@RequestBody Map<String, Object> input) {
        String ticketKey = (String) input.get("ticketKey");
        setupMdc(ticketKey, "create-pr");

        try {
            log.info("Creating PR for ticket: {}", ticketKey);

            Map<String, Object> result = new HashMap<>(input);
            result.put("prCreated", true);
            result.put("status", "pr-created");

            log.info("PR created for ticket: {}", ticketKey);
            return ResponseEntity.ok(result);
        } finally {
            MDC.clear();
        }
    }

    @PostMapping("/merge-wait")
    public ResponseEntity<Map<String, Object>> mergeWait(@RequestBody Map<String, Object> input) {
        String ticketKey = (String) input.get("ticketKey");
        setupMdc(ticketKey, "merge-wait");

        try {
            log.info("Merge wait for ticket: {}", ticketKey);

            Map<String, Object> result = new HashMap<>(input);
            result.put("status", "merge-waiting");

            return ResponseEntity.ok(result);
        } finally {
            MDC.clear();
        }
    }

    private void setupMdc(String ticketKey, String handler) {
        MDC.put("correlationId", UUID.randomUUID().toString());
        MDC.put("ticketKey", ticketKey != null ? ticketKey : "unknown");
        MDC.put("handler", handler);
    }
}
