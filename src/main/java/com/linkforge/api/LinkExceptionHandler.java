package com.linkforge.api;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.linkforge.api.dto.ApiErrorResponse;
import com.linkforge.domain.link.exception.AliasConflictException;
import com.linkforge.domain.link.exception.InvalidAliasException;
import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
import com.linkforge.domain.link.exception.LinkNotFoundException;
import com.linkforge.domain.workflow.scenario.exception.InspectionException;
import com.linkforge.domain.workflow.scenario.exception.InspectionSecurityException;
import com.linkforge.service.security.InvalidPlanHashException;
import com.linkforge.service.security.WorkflowAuthorizationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class LinkExceptionHandler {

    @ExceptionHandler(InvalidDestinationUrlException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidDestinationUrl(InvalidDestinationUrlException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_DESTINATION_URL", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(InvalidAliasException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAlias(InvalidAliasException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ALIAS", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(AliasConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleAliasConflict(AliasConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("ALIAS_CONFLICT", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleLinkNotFound(LinkNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("LINK_NOT_FOUND", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(com.linkforge.domain.workflow.exception.WorkflowNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkflowNotFound(com.linkforge.domain.workflow.exception.WorkflowNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("WORKFLOW_NOT_FOUND", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(InspectionSecurityException.class)
    public ResponseEntity<ApiErrorResponse> handleInspectionSecurity(InspectionSecurityException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("SECURITY_VIOLATION", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(InspectionException.class)
    public ResponseEntity<ApiErrorResponse> handleInspectionException(InspectionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INSPECTION_ERROR", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(WorkflowAuthorizationException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkflowAuthorization(WorkflowAuthorizationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("UNAUTHORIZED", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(InvalidPlanHashException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPlanHash(InvalidPlanHashException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_PLAN_HASH", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("INVALID_WORKFLOW_STATE", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("BAD_REQUEST", sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException unrecognized) {
            String propName = unrecognized.getPropertyName();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiErrorResponse.of(
                            "UNRECOGNIZED_PROPERTY",
                            "Unrecognized or caller-controlled property '" + propName + "' is rejected."
                    ));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("MALFORMED_REQUEST", "Malformed JSON request body: " + sanitizeMessage(ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_FAILED", message.isBlank() ? "Validation failed" : sanitizeMessage(message)));
    }

    private String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "An error occurred.";
        }
        String sanitized = message;
        // Redact bearer tokens or secrets
        sanitized = sanitized.replaceAll("(?i)(bearer\\s+)[a-zA-Z0-9_.-]+", "$1[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)(token\\s*[:=]\\s*)[a-zA-Z0-9_.-]+", "$1[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)(dev-[a-z]+-token)", "[REDACTED_TOKEN]");
        // Redact absolute local filesystem paths
        sanitized = sanitized.replaceAll("/Users/[^\\s,;\"'<>]+", "[REDACTED_PATH]");
        sanitized = sanitized.replaceAll("/home/[^\\s,;\"'<>]+", "[REDACTED_PATH]");
        sanitized = sanitized.replaceAll("/var/[^\\s,;\"'<>]+", "[REDACTED_PATH]");
        sanitized = sanitized.replaceAll("/private/var/[^\\s,;\"'<>]+", "[REDACTED_PATH]");
        sanitized = sanitized.replaceAll("[A-Za-z]:\\\\[^\\s,;\"'<>]+", "[REDACTED_PATH]");
        return sanitized;
    }
}
