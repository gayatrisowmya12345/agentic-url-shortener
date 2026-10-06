package com.linkforge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitClarificationRequest(
        @NotBlank(message = "Clarification cannot be blank")
        String clarification,
        String repositoryPath
) {
    public SubmitClarificationRequest(String clarification) {
        this(clarification, null);
    }
}
