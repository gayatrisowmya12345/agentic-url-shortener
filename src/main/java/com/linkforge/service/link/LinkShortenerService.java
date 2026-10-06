package com.linkforge.service.link;

import com.linkforge.domain.link.ClickEvent;
import com.linkforge.domain.link.Link;
import com.linkforge.domain.link.exception.AliasConflictException;
import com.linkforge.domain.link.exception.InvalidAliasException;
import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
import com.linkforge.domain.link.exception.LinkNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service managing URL shortening, custom alias conflict detection,
 * HTTP 302 resolution, and atomic click analytics tracking with H2 persistence.
 */
@Service
public class LinkShortenerService {

    private static final String BASE62_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int DEFAULT_TOKEN_LENGTH = 7;
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");
    private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
            "api", "actuator", "r", "links", "workflows", "health", "metrics", "swagger", "error"
    );

    private final LinkRepository linkRepository;
    private final SecureRandom random = new SecureRandom();

    public LinkShortenerService(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Link createShortLink(String destinationUrl, String customAlias) {
        validateDestinationUrl(destinationUrl);

        String trimmedAlias = customAlias != null ? customAlias.trim() : null;
        if (trimmedAlias != null && trimmedAlias.isEmpty()) {
            trimmedAlias = null;
        }

        if (trimmedAlias != null) {
            validateCustomAlias(trimmedAlias);
            if (linkRepository.existsByIdentifier(trimmedAlias)) {
                throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
            }
        }

        // Retry loop for unique token generation in the event of concurrent token collision
        for (int attempts = 0; attempts < 10; attempts++) {
            String token = generateUniqueToken();
            Link link = new Link(token, trimmedAlias, destinationUrl.trim());
            try {
                return linkRepository.save(link);
            } catch (DuplicateKeyException e) {
                // If the conflict was on custom alias (e.g. concurrent creation), rethrow AliasConflictException
                if (trimmedAlias != null && linkRepository.existsByIdentifier(trimmedAlias)) {
                    throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                }
                // Otherwise a generated token collided concurrently; loop and regenerate
            }
        }
        throw new IllegalStateException("Failed to generate a non-colliding token after multiple attempts.");
    }

    @Transactional
    public Link resolveLink(String identifier, String referrer, String userAgent) {
        Link link = getLink(identifier);
        linkRepository.recordClick(link.getId(), referrer, userAgent);
        return getLink(identifier);
    }

    public Link getLink(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new LinkNotFoundException("Link identifier cannot be empty.");
        }
        return linkRepository.findByIdentifier(identifier.trim())
                .orElseThrow(() -> new LinkNotFoundException("Short link not found for identifier: '" + identifier + "'"));
    }

    public Link getLinkAnalytics(String identifier) {
        Link link = getLink(identifier);
        List<ClickEvent> recentClicks = linkRepository.findRecentClicks(link.getId(), 50);
        return new Link(
                link.getId(),
                link.getToken(),
                link.getCustomAlias(),
                link.getDestinationUrl(),
                link.getCreatedAt(),
                link.getClickCount(),
                link.getLastClickedAt(),
                recentClicks
        );
    }

    private void validateDestinationUrl(String destinationUrl) {
        if (destinationUrl == null || destinationUrl.isBlank()) {
            throw new InvalidDestinationUrlException("Destination URL must not be blank.");
        }

        try {
            URI uri = URI.create(destinationUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new InvalidDestinationUrlException("Destination URL must use http or https scheme.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new InvalidDestinationUrlException("Destination URL must contain a valid hostname.");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidDestinationUrlException("Destination URL is malformed: " + e.getMessage());
        }
    }

    private void validateCustomAlias(String alias) {
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidAliasException("Custom alias must be between 3 and 64 characters and contain only alphanumeric characters, underscores, or hyphens.");
        }
        if (RESERVED_IDENTIFIERS.contains(alias.toLowerCase())) {
            throw new InvalidAliasException("Custom alias '" + alias + "' is reserved by the system.");
        }
    }

    private String generateUniqueToken() {
        for (int attempts = 0; attempts < 100; attempts++) {
            StringBuilder sb = new StringBuilder(DEFAULT_TOKEN_LENGTH);
            for (int i = 0; i < DEFAULT_TOKEN_LENGTH; i++) {
                int index = random.nextInt(BASE62_ALPHABET.length());
                sb.append(BASE62_ALPHABET.charAt(index));
            }
            String candidate = sb.toString();
            if (!linkRepository.existsByIdentifier(candidate) && !RESERVED_IDENTIFIERS.contains(candidate.toLowerCase())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique token after multiple attempts.");
    }
}
