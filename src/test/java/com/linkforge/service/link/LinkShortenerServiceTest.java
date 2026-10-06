package com.linkforge.service.link;

import com.linkforge.domain.link.Link;
import com.linkforge.domain.link.exception.AliasConflictException;
import com.linkforge.domain.link.exception.InvalidAliasException;
import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
import com.linkforge.domain.link.exception.LinkNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LinkShortenerServiceTest {

    @Autowired
    private LinkRepository repository;

    @Autowired
    private LinkShortenerService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        repository.clear();
    }

    @Test
    @DisplayName("Successfully creates short link with generated token and persists in H2")
    void createShortLinkGeneratedToken() {
        Link link = service.createShortLink("https://www.example.com/very/long/url", null);

        assertThat(link).isNotNull();
        assertThat(link.getId()).isNotBlank();
        assertThat(link.getToken()).isNotBlank();
        assertThat(link.getToken()).hasSize(7);
        assertThat(link.getCustomAlias()).isNull();
        assertThat(link.getDestinationUrl()).isEqualTo("https://www.example.com/very/long/url");
        assertThat(link.getClickCount()).isZero();
        assertThat(link.getCreatedAt()).isNotNull();

        // Verify direct H2 persistence
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM links WHERE token = ?",
                Integer.class,
                link.getToken()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Successfully creates short link with custom alias and persists in H2")
    void createShortLinkCustomAlias() {
        Link link = service.createShortLink("https://github.com/spring-projects", "spring-boot");

        assertThat(link).isNotNull();
        assertThat(link.getCustomAlias()).isEqualTo("spring-boot");
        assertThat(link.getEffectiveIdentifier()).isEqualTo("spring-boot");
        assertThat(link.getDestinationUrl()).isEqualTo("https://github.com/spring-projects");

        // Can find by both alias and generated token in H2
        assertThat(repository.findByIdentifier("spring-boot")).isPresent();
        assertThat(repository.findByIdentifier(link.getToken())).isPresent();

        // Verify direct H2 table record
        Integer aliasCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM links WHERE custom_alias = 'spring-boot'",
                Integer.class
        );
        assertThat(aliasCount).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://files.example.com",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "htp://typo.com",
            "http://",
            "not-a-url",
            ""
    })
    @DisplayName("Rejects invalid or non-HTTP/HTTPS destination URLs")
    void rejectsInvalidDestinationUrls(String invalidUrl) {
        assertThatThrownBy(() -> service.createShortLink(invalidUrl, null))
                .isInstanceOf(InvalidDestinationUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ab",                 // Too short (< 3)
            "my alias with spaces",
            "alias!@#$%",
            "r",                  // Reserved
            "api"                 // Reserved
    })
    @DisplayName("Rejects invalid custom alias formats and reserved words")
    void rejectsInvalidCustomAliases(String invalidAlias) {
        assertThatThrownBy(() -> service.createShortLink("https://example.com", invalidAlias))
                .isInstanceOf(InvalidAliasException.class);
    }

    @Test
    @DisplayName("Rejects duplicate custom alias with AliasConflictException")
    void rejectsDuplicateCustomAlias() {
        service.createShortLink("https://first.example.com", "my-promo");

        assertThatThrownBy(() -> service.createShortLink("https://second.example.com", "my-promo"))
                .isInstanceOf(AliasConflictException.class)
                .hasMessageContaining("my-promo")
                .hasMessageContaining("already in use");
    }

    @Test
    @DisplayName("Concurrent duplicate custom alias requests safely handle conflict without overwriting")
    void concurrentDuplicateCustomAliasHandledSafely() throws Exception {
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    service.createShortLink("https://target-" + index + ".com", "race-alias");
                    successCount.incrementAndGet();
                    return true;
                } catch (AliasConflictException e) {
                    conflictCount.incrementAndGet();
                    return false;
                }
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();

        for (Future<Boolean> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);

        // Verify only 1 record exists in H2
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM links WHERE custom_alias = 'race-alias'",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent clicks are recorded atomically in H2 without losing click counts or events")
    void concurrentClicksRecordedWithoutLoss() throws Exception {
        Link link = service.createShortLink("https://example.com/analytics-test", "concurrent-click");

        int clickThreads = 25;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < clickThreads; i++) {
            final int index = i;
            tasks.add(() -> {
                startLatch.await();
                service.resolveLink("concurrent-click", "https://ref-" + index + ".com", "Agent/" + index);
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }

        startLatch.countDown();

        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Verify analytics via service
        Link updated = service.getLinkAnalytics("concurrent-click");
        assertThat(updated.getClickCount()).isEqualTo(clickThreads);

        // Verify directly in H2 database tables
        Long dbCount = jdbcTemplate.queryForObject(
                "SELECT click_count FROM links WHERE id = ?",
                Long.class,
                link.getId()
        );
        assertThat(dbCount).isEqualTo((long) clickThreads);

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM click_events WHERE link_id = ?",
                Integer.class,
                link.getId()
        );
        assertThat(eventCount).isEqualTo(clickThreads);
    }

    @Test
    @DisplayName("Resolving link increments click count and records timestamp and event")
    void resolveLinkRecordsClick() {
        Link link = service.createShortLink("https://example.com", "promo2026");
        assertThat(link.getClickCount()).isZero();

        Link resolved1 = service.resolveLink("promo2026", "https://twitter.com", "Mozilla/5.0");
        assertThat(resolved1.getClickCount()).isEqualTo(1);
        assertThat(resolved1.getLastClickedAt()).isNotNull();

        Link resolved2 = service.resolveLink("promo2026", "https://google.com", "Chrome/120");
        assertThat(resolved2.getClickCount()).isEqualTo(2);

        Link analytics = service.getLinkAnalytics("promo2026");
        assertThat(analytics.getClickCount()).isEqualTo(2);
        assertThat(analytics.getClickEvents()).hasSize(2);
    }

    @Test
    @DisplayName("Throws LinkNotFoundException when resolving unknown token or alias")
    void throwsWhenLinkNotFound() {
        assertThatThrownBy(() -> service.resolveLink("non-existent-token", null, null))
                .isInstanceOf(LinkNotFoundException.class)
                .hasMessageContaining("non-existent-token");

        assertThatThrownBy(() -> service.getLinkAnalytics("non-existent-token"))
                .isInstanceOf(LinkNotFoundException.class);
    }
}
