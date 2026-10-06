package com.linkforge.api.dto;

import com.linkforge.domain.link.Link;

import java.time.Instant;

public record LinkResponse(
        String id,
        String token,
        String customAlias,
        String destinationUrl,
        String shortUrl,
        long clickCount,
        Instant createdAt,
        Instant lastClickedAt
) {
    public static LinkResponse from(Link link) {
        String effectiveId = link.getEffectiveIdentifier();
        return new LinkResponse(
                link.getId(),
                link.getToken(),
                link.getCustomAlias(),
                link.getDestinationUrl(),
                "/r/" + effectiveId,
                link.getClickCount(),
                link.getCreatedAt(),
                link.getLastClickedAt()
        );
    }
}
