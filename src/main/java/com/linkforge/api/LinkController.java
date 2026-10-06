package com.linkforge.api;

import com.linkforge.api.dto.CreateLinkRequest;
import com.linkforge.api.dto.LinkAnalyticsResponse;
import com.linkforge.api.dto.LinkResponse;
import com.linkforge.domain.link.Link;
import com.linkforge.service.link.LinkShortenerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkShortenerService linkShortenerService;

    public LinkController(LinkShortenerService linkShortenerService) {
        this.linkShortenerService = linkShortenerService;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> createLink(@Valid @RequestBody CreateLinkRequest request) {
        Link link = linkShortenerService.createShortLink(request.destinationUrl(), request.customAlias());
        URI location = URI.create("/api/v1/links/" + link.getEffectiveIdentifier());
        return ResponseEntity.created(location).body(LinkResponse.from(link));
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<LinkResponse> getLink(@PathVariable String identifier) {
        Link link = linkShortenerService.getLink(identifier);
        return ResponseEntity.ok(LinkResponse.from(link));
    }

    @GetMapping("/{identifier}/analytics")
    public ResponseEntity<LinkAnalyticsResponse> getLinkAnalytics(@PathVariable String identifier) {
        Link link = linkShortenerService.getLinkAnalytics(identifier);
        return ResponseEntity.ok(LinkAnalyticsResponse.from(link));
    }

    @GetMapping("/{identifier}/redirect")
    public ResponseEntity<Void> redirectLink(
            @PathVariable String identifier,
            @RequestHeader(value = HttpHeaders.REFERER, required = false) String referrer,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent
    ) {
        Link link = linkShortenerService.resolveLink(identifier, referrer, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.getDestinationUrl()))
                .build();
    }
}
