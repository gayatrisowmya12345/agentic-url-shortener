package com.linkforge.domain.link;

import java.time.Instant;
import java.util.UUID;

public record ClickEvent(
        String id,
        Instant timestamp,
        String referrer,
        String userAgent
) {
    public static ClickEvent now(String referrer, String userAgent) {
        return new ClickEvent(UUID.randomUUID().toString(), Instant.now(), referrer, userAgent);
    }
}
