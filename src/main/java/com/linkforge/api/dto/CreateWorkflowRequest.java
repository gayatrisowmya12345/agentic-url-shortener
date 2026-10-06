package com.linkforge.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateWorkflowRequest(
        @NotBlank(message = "Requirement cannot be blank")
        String requirement,
        String repositoryPath
) {
    public CreateWorkflowRequest(String requirement) {
        this(requirement, null);
    }
}
