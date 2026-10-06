package com.linkforge.api.dto;

import com.linkforge.domain.link.ClickEvent;

import java.time.Instant;

public record ClickEventResponse(
        String id,
        Instant timestamp,
        String referrer,
        String userAgent
) {
    public static ClickEventResponse from(ClickEvent event) {
        return new ClickEventResponse(
                event.id(),
                event.timestamp(),
                event.referrer(),
                event.userAgent()
        );
    }
}
