package com.linkforge.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ExecuteWorkflowRequest(
        @NotBlank(message = "Plan hash is required for execution.")
        String planHash
) {}
