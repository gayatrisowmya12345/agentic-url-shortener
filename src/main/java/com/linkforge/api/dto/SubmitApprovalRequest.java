package com.linkforge.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SubmitApprovalRequest(
        @NotBlank(message = "Decision cannot be blank (APPROVED or REJECTED)")
        String decision,
        @NotBlank(message = "Plan hash cannot be blank")
        String planHash,
        String approver,
        String comments
) {
    public SubmitApprovalRequest(String decision, String planHash) {
        this(decision, planHash, null, null);
    }
}
