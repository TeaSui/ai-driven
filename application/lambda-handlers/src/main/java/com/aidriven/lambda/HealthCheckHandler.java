package com.aidriven.lambda;

import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Basic health check handler for monitoring and validation.
 */
@Slf4j
public class HealthCheckHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final ServiceFactory serviceFactory;

    public HealthCheckHandler() {
        this.serviceFactory = ServiceFactory.getInstance();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        log.info("Health check received");

        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", System.currentTimeMillis());
        result.put("version", "1.0.0");

        try {
            // Basic dependency check (DynamoDB connection)
            serviceFactory.getDynamoDbClient().listTables(req -> req.limit(1));
            result.put("dynamoDb", "OK");
        } catch (Exception e) {
            log.error("Health check: DynamoDB check failed", e);
            result.put("dynamoDb", "ERROR: " + e.getMessage());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 200);
        response.put("headers", Map.of("Content-Type", "application/json"));

        try {
            response.put("body", serviceFactory.getObjectMapper().writeValueAsString(result));
        } catch (Exception e) {
            response.put("body", "{\"status\":\"UP\",\"message\":\"Error serializing detail\"}");
        }

        return response;
    }
}
