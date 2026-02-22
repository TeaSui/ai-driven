package com.teasui.common.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void success_withData_shouldReturnSuccessResponse() {
        String data = "test data";
        ApiResponse<String> response = ApiResponse.success(data);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(data);
        assertThat(response.getErrors()).isNull();
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void success_withDataAndMessage_shouldReturnSuccessResponse() {
        String data = "test data";
        String message = "Operation successful";
        ApiResponse<String> response = ApiResponse.success(data, message);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(data);
        assertThat(response.getMessage()).isEqualTo(message);
    }

    @Test
    void error_withMessage_shouldReturnErrorResponse() {
        String message = "Something went wrong";
        ApiResponse<Void> response = ApiResponse.error(message);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo(message);
        assertThat(response.getData()).isNull();
    }

    @Test
    void error_withMessageAndErrors_shouldReturnErrorResponseWithDetails() {
        String message = "Validation failed";
        List<String> errors = List.of("Field A is required", "Field B must be positive");
        ApiResponse<Void> response = ApiResponse.error(message, errors);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo(message);
        assertThat(response.getErrors()).containsExactlyElementsOf(errors);
    }
}
