package com.linkforge.service.implementation;

import java.util.Locale;

/**
 * Explicit scope definitions for supported URL-shortener changes in LinkForge.
 * Requests outside these scopes are recognized as unsupported and handled safely.
 */
public enum ImplementationScope {
    ALIAS_VALIDATION("Custom Alias Validation", "Validates short link custom aliases with format and length constraints."),
    DOMAIN_RESTRICTION("Destination Domain Security", "Validates destination URLs against trusted domain policies."),
    TOKEN_POLICY("Token Generation Policy", "Configures Base62 short token length and uniqueness constraints."),
    CLICK_ANALYTICS("Click Analytics Tracking", "Enhances click resolution tracking and analytics filters."),
    URL_SHORTENER_CORE("Core URL Shortener Service", "Core URL shortener routing, storage, and link policies."),
    GREENFIELD_SERVICE("Greenfield URL Shortener Service", "Builds a standalone URL shortener service from an empty source baseline."),
    UNKNOWN("Unsupported Scope", "Requirement falls outside the supported URL-shortener engineering workbench scope.");

    private final String displayName;
    private final String description;

    ImplementationScope(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static ImplementationScope detectScope(String requirementText) {
        if (requirementText == null || requirementText.isBlank()) {
            return UNKNOWN;
        }
        String lower = requirementText.toLowerCase(Locale.ROOT);

        // Check for clear non-URL-shortener indicators first
        if (lower.contains("blockchain") || lower.contains("crypto") || lower.contains("bitcoin")
                || lower.contains("shopping cart") || lower.contains("e-commerce") || lower.contains("neural net")
                || lower.contains("image recognition") || lower.contains("game engine") || lower.contains("flight booking")) {
            return UNKNOWN;
        }

        if (lower.contains("greenfield") || lower.contains("empty source baseline") || lower.contains("from scratch") || lower.contains("standalone url shortener")) {
            return GREENFIELD_SERVICE;
        }

        if (lower.contains("alias") || lower.contains("custom alias")) {
            return ALIAS_VALIDATION;
        }
        if (lower.contains("domain") || lower.contains("https-only") || lower.contains("allowed hosts") || lower.contains("trusted host")) {
            return DOMAIN_RESTRICTION;
        }
        if (lower.contains("token") || lower.contains("base62") || lower.contains("nanoid") || lower.contains("random token")) {
            return TOKEN_POLICY;
        }
        if (lower.contains("analytics") || lower.contains("click count") || lower.contains("referrer") || lower.contains("metrics")) {
            return CLICK_ANALYTICS;
        }
        if (lower.contains("url shortener") || lower.contains("short link") || lower.contains("shorten") || lower.contains("links")) {
            return URL_SHORTENER_CORE;
        }

        return UNKNOWN;
    }
}
