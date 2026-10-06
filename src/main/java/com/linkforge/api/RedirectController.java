package com.linkforge.api;

import com.linkforge.domain.link.Link;
import com.linkforge.service.link.LinkShortenerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/r")
public class RedirectController {

    private final LinkShortenerService linkShortenerService;

    public RedirectController(LinkShortenerService linkShortenerService) {
        this.linkShortenerService = linkShortenerService;
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<Void> redirect(
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
