package com.linkforge.api;

import com.linkforge.api.dto.ApiErrorResponse;
import com.linkforge.domain.link.exception.AliasConflictException;
import com.linkforge.domain.link.exception.InvalidAliasException;
import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
import com.linkforge.domain.link.exception.LinkNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
                .body(ApiErrorResponse.of("INVALID_DESTINATION_URL", ex.getMessage()));
    }

    @ExceptionHandler(InvalidAliasException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAlias(InvalidAliasException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ALIAS", ex.getMessage()));
    }

    @ExceptionHandler(AliasConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleAliasConflict(AliasConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("ALIAS_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleLinkNotFound(LinkNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("LINK_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_FAILED", message.isBlank() ? "Validation failed" : message));
    }
}
