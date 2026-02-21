package com.teasui.crm.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ApiResponse Tests")
class ApiResponseTest {

    @Test
    @DisplayName("Should create success response with data")
    void shouldCreateSuccessResponseWithData() {
        ApiResponse<String> response = ApiResponse.success("test data");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("test data");
        assertThat(response.getErrors()).isNull();
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("Should create success response with data and message")
    void shouldCreateSuccessResponseWithDataAndMessage() {
        ApiResponse<String> response = ApiResponse.success("test data", "Operation successful");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("test data");
        assertThat(response.getMessage()).isEqualTo("Operation successful");
    }

    @Test
    @DisplayName("Should create error response with message")
    void shouldCreateErrorResponseWithMessage() {
        ApiResponse<Void> response = ApiResponse.error("Something went wrong");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Something went wrong");
        assertThat(response.getData()).isNull();
    }

    @Test
    @DisplayName("Should create error response with errors list")
    void shouldCreateErrorResponseWithErrorsList() {
        List<String> errors = List.of("Field 1 is required", "Field 2 is invalid");
        ApiResponse<Void> response = ApiResponse.error("Validation failed", errors);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Validation failed");
        assertThat(response.getErrors()).containsExactlyElementsOf(errors);
    }
}
