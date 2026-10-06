package com.linkforge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(
        @NotBlank(message = "Destination URL cannot be blank")
        String destinationUrl,
        String customAlias
) {
}
