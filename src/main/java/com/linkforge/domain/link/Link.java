package com.linkforge.domain.link;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Domain entity representing a shortened URL and its associated click metrics.
 */
public class Link {

    private final String id;
    private final String token;
    private final String customAlias;
    private final String destinationUrl;
    private final Instant createdAt;
    private final AtomicLong clickCount;
    private volatile Instant lastClickedAt;
    private final List<ClickEvent> clickEvents;

    public Link(String token, String customAlias, String destinationUrl) {
        this(UUID.randomUUID().toString(), token, customAlias, destinationUrl, Instant.now(), 0L, null, new ArrayList<>());
    }

    public Link(String id, String token, String customAlias, String destinationUrl, Instant createdAt, long clickCount, Instant lastClickedAt, List<ClickEvent> clickEvents) {
        this.id = id;
        this.token = token;
        this.customAlias = customAlias;
        this.destinationUrl = destinationUrl;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.clickCount = new AtomicLong(clickCount);
        this.lastClickedAt = lastClickedAt;
        this.clickEvents = Collections.synchronizedList(new ArrayList<>(clickEvents != null ? clickEvents : List.of()));
    }

    public synchronized void recordClick(String referrer, String userAgent) {
        this.clickCount.incrementAndGet();
        this.lastClickedAt = Instant.now();
        this.clickEvents.add(ClickEvent.now(referrer, userAgent));
    }

    public String getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getCustomAlias() {
        return customAlias;
    }

    public String getEffectiveIdentifier() {
        return customAlias != null && !customAlias.isBlank() ? customAlias : token;
    }

    public String getDestinationUrl() {
        return destinationUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getClickCount() {
        return clickCount.get();
    }

    public Instant getLastClickedAt() {
        return lastClickedAt;
    }

    public List<ClickEvent> getClickEvents() {
        synchronized (clickEvents) {
            return new ArrayList<>(clickEvents);
        }
    }
}
