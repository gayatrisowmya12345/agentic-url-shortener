package com.linkforge.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SubmitClarificationRequest(
        @NotBlank(message = "Clarification cannot be blank")
        String clarification,
        String repositoryPath,
        String submittedBy
) {
    public SubmitClarificationRequest(String clarification) {
        this(clarification, null, null);
    }

    public SubmitClarificationRequest(String clarification, String repositoryPath) {
        this(clarification, repositoryPath, null);
    }
}
