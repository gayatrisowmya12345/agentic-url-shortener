package com.linkforge.api.dto;

import com.linkforge.domain.link.Link;

import java.time.Instant;
import java.util.List;

public record LinkAnalyticsResponse(
        String token,
        String customAlias,
        String destinationUrl,
        String shortUrl,
        long clickCount,
        Instant createdAt,
        Instant lastClickedAt,
        List<ClickEventResponse> recentClicks
) {
    public static LinkAnalyticsResponse from(Link link) {
        String effectiveId = link.getEffectiveIdentifier();
        List<ClickEventResponse> clicks = link.getClickEvents().stream()
                .map(ClickEventResponse::from)
                .toList();

        return new LinkAnalyticsResponse(
                link.getToken(),
                link.getCustomAlias(),
                link.getDestinationUrl(),
                "/r/" + effectiveId,
                link.getClickCount(),
                link.getCreatedAt(),
                link.getLastClickedAt(),
                clicks
        );
    }
}
