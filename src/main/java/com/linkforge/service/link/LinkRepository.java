package com.linkforge.service.link;

import com.linkforge.domain.link.ClickEvent;
import com.linkforge.domain.link.Link;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * H2-backed JDBC repository for shortened links and click events.
 */
@Repository
public class LinkRepository {

    private final JdbcTemplate jdbcTemplate;

    public LinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Link save(Link link) {
        if (link == null) {
            throw new IllegalArgumentException("Link cannot be null");
        }

        jdbcTemplate.update(
                "INSERT INTO links (id, token, custom_alias, destination_url, click_count, created_at, last_clicked_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                link.getId(),
                link.getToken(),
                link.getCustomAlias(),
                link.getDestinationUrl(),
                link.getClickCount(),
                Timestamp.from(link.getCreatedAt()),
                link.getLastClickedAt() != null ? Timestamp.from(link.getLastClickedAt()) : null
        );
        return link;
    }

    public Optional<Link> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }

        String sql = "SELECT id, token, custom_alias, destination_url, click_count, created_at, last_clicked_at " +
                "FROM links WHERE token = ? OR custom_alias = ? OR id = ?";
        try {
            Link link = jdbcTemplate.queryForObject(sql, new LinkRowMapper(), identifier.trim(), identifier.trim(), identifier.trim());
            return Optional.ofNullable(link);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Link> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        String sql = "SELECT id, token, custom_alias, destination_url, click_count, created_at, last_clicked_at " +
                "FROM links WHERE id = ?";
        try {
            Link link = jdbcTemplate.queryForObject(sql, new LinkRowMapper(), id.trim());
            return Optional.ofNullable(link);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean existsByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM links WHERE token = ? OR custom_alias = ? OR id = ?",
                Integer.class,
                identifier.trim(),
                identifier.trim(),
                identifier.trim()
        );
        return count != null && count > 0;
    }

    @Transactional
    public void recordClick(String linkId, String referrer, String userAgent) {
        Instant now = Instant.now();
        String eventId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO click_events (id, link_id, clicked_at, referrer, user_agent) VALUES (?, ?, ?, ?, ?)",
                eventId,
                linkId,
                Timestamp.from(now),
                referrer,
                userAgent
        );

        jdbcTemplate.update(
                "UPDATE links SET click_count = click_count + 1, last_clicked_at = ? WHERE id = ?",
                Timestamp.from(now),
                linkId
        );
    }

    public List<ClickEvent> findRecentClicks(String linkId, int limit) {
        String sql = "SELECT id, clicked_at, referrer, user_agent FROM click_events " +
                "WHERE link_id = ? ORDER BY clicked_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ClickEvent(
                rs.getString("id"),
                toInstant(rs.getTimestamp("clicked_at")),
                rs.getString("referrer"),
                rs.getString("user_agent")
        ), linkId, limit);
    }

    public List<Link> findAll() {
        return jdbcTemplate.query(
                "SELECT id, token, custom_alias, destination_url, click_count, created_at, last_clicked_at FROM links ORDER BY created_at DESC",
                new LinkRowMapper()
        );
    }

    public void clear() {
        jdbcTemplate.update("DELETE FROM click_events");
        jdbcTemplate.update("DELETE FROM links");
    }

    private static class LinkRowMapper implements RowMapper<Link> {
        @Override
        public Link mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Link(
                    rs.getString("id"),
                    rs.getString("token"),
                    rs.getString("custom_alias"),
                    rs.getString("destination_url"),
                    toInstant(rs.getTimestamp("created_at")),
                    rs.getLong("click_count"),
                    toInstant(rs.getTimestamp("last_clicked_at")),
                    new ArrayList<>()
            );
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
