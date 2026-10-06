package com.linkforge.api;

import com.linkforge.service.link.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LinkControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        linkRepository.clear();
    }

    @Test
    @DisplayName("POST /api/v1/links creates short link with generated token")
    void createShortLinkWithGeneratedToken() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://spring.io/projects/spring-boot"
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/links/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.destinationUrl").value("https://spring.io/projects/spring-boot"))
                .andExpect(jsonPath("$.shortUrl", startsWith("/r/")))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/links creates short link with custom alias")
    void createShortLinkWithCustomAlias() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://developer.mozilla.org/en-US/",
                  "customAlias": "mdn-web-docs"
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", equalTo("/api/v1/links/mdn-web-docs")))
                .andExpect(jsonPath("$.customAlias").value("mdn-web-docs"))
                .andExpect(jsonPath("$.destinationUrl").value("https://developer.mozilla.org/en-US/"))
                .andExpect(jsonPath("$.shortUrl").value("/r/mdn-web-docs"))
                .andExpect(jsonPath("$.clickCount").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/links rejects blank destination URL with 400 Bad Request")
    void createShortLinkBlankDestinationUrl() throws Exception {
        String payload = """
                {
                  "destinationUrl": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/links rejects non-HTTP/HTTPS schemes with 400 Bad Request")
    void createShortLinkInvalidScheme() throws Exception {
        String payload = """
                {
                  "destinationUrl": "ftp://files.example.com/data.zip"
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_DESTINATION_URL"))
                .andExpect(jsonPath("$.message").value("Destination URL must use http or https scheme."));
    }

    @Test
    @DisplayName("POST /api/v1/links rejects invalid custom alias with 400 Bad Request")
    void createShortLinkInvalidAliasFormat() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://example.com",
                  "customAlias": "a b c"
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ALIAS"));
    }

    @Test
    @DisplayName("POST /api/v1/links rejects duplicate custom alias with 409 Conflict")
    void createShortLinkAliasConflict() throws Exception {
        String firstPayload = """
                {
                  "destinationUrl": "https://first.example.com",
                  "customAlias": "popular-link"
                }
                """;
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isCreated());

        String duplicatePayload = """
                {
                  "destinationUrl": "https://second.example.com",
                  "customAlias": "popular-link"
                }
                """;
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicatePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALIAS_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Custom alias 'popular-link' is already in use."));
    }

    @Test
    @DisplayName("GET /api/v1/links/{identifier} returns link metadata")
    void getLinkSuccess() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://example.com/target",
                  "customAlias": "target-page"
                }
                """;
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/links/target-page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customAlias").value("target-page"))
                .andExpect(jsonPath("$.destinationUrl").value("https://example.com/target"));
    }

    @Test
    @DisplayName("GET /api/v1/links/{identifier} returns 404 for unknown link")
    void getLinkNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/links/unknown-token-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /r/{identifier} issues HTTP 302 Found redirect to destination URL")
    void redirectLinkViaShortEndpoint() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://docs.oracle.com/en/java/",
                  "customAlias": "java-docs"
                }
                """;
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/r/java-docs")
                        .header(HttpHeaders.REFERER, "https://google.com")
                        .header(HttpHeaders.USER_AGENT, "TestBrowser/1.0"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://docs.oracle.com/en/java/"));
    }

    @Test
    @DisplayName("GET /api/v1/links/{identifier}/redirect issues HTTP 302 Found redirect")
    void redirectLinkViaApiEndpoint() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://example.org/api-redirect",
                  "customAlias": "api-redir"
                }
                """;
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/links/api-redir/redirect"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.org/api-redirect"));
    }

    @Test
    @DisplayName("GET /r/{identifier} returns 404 Not Found for non-existent token")
    void redirectLinkNotFound() throws Exception {
        mockMvc.perform(get("/r/missing-code"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("Recording and retrieving basic click analytics for a short link")
    void recordAndRetrieveClickAnalytics() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://news.ycombinator.com",
                  "customAlias": "hackernews"
                }
                """;
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // Verify initial analytics: 0 clicks
        mockMvc.perform(get("/api/v1/links/hackernews/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customAlias").value("hackernews"))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.lastClickedAt").doesNotExist())
                .andExpect(jsonPath("$.recentClicks", empty()));

        // Perform 2 redirects
        mockMvc.perform(get("/r/hackernews")
                        .header(HttpHeaders.REFERER, "https://news-aggregator.com")
                        .header(HttpHeaders.USER_AGENT, "Safari/17.0"))
                .andExpect(status().isFound());

        mockMvc.perform(get("/r/hackernews")
                        .header(HttpHeaders.REFERER, "https://reddit.com")
                        .header(HttpHeaders.USER_AGENT, "Chrome/122.0"))
                .andExpect(status().isFound());

        // Verify updated analytics
        mockMvc.perform(get("/api/v1/links/hackernews/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customAlias").value("hackernews"))
                .andExpect(jsonPath("$.clickCount").value(2))
                .andExpect(jsonPath("$.lastClickedAt", notNullValue()))
                .andExpect(jsonPath("$.recentClicks", hasSize(2)))
                .andExpect(jsonPath("$.recentClicks[0].referrer").value("https://reddit.com"))
                .andExpect(jsonPath("$.recentClicks[1].referrer").value("https://news-aggregator.com"));
    }

    @Test
    @DisplayName("GET /api/v1/links/{identifier}/analytics returns 404 for unknown link")
    void getAnalyticsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/links/unknown-token/analytics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("Created links and click events are directly verifiable in H2 database tables")
    void createdLinkPersistedDirectlyInH2Database() throws Exception {
        String payload = """
                {
                  "destinationUrl": "https://docs.spring.io",
                  "customAlias": "spring-docs"
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // Verify in H2 links table
        Integer linkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM links WHERE custom_alias = 'spring-docs'",
                Integer.class
        );
        assertThat(linkCount).isEqualTo(1);

        // Perform redirect
        mockMvc.perform(get("/r/spring-docs"))
                .andExpect(status().isFound());

        // Verify in H2 click_events table
        Integer clickCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM click_events ce JOIN links l ON ce.link_id = l.id WHERE l.custom_alias = 'spring-docs'",
                Integer.class
        );
        assertThat(clickCount).isEqualTo(1);
    }
}
