CREATE TABLE IF NOT EXISTS links (
    id VARCHAR(36) PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    custom_alias VARCHAR(64) UNIQUE,
    destination_url VARCHAR(2048) NOT NULL,
    click_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_clicked_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS click_events (
    id VARCHAR(36) PRIMARY KEY,
    link_id VARCHAR(36) NOT NULL,
    clicked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    referrer VARCHAR(1024),
    user_agent VARCHAR(1024),
    CONSTRAINT fk_click_events_link FOREIGN KEY (link_id) REFERENCES links(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_links_token ON links(token);
CREATE INDEX IF NOT EXISTS idx_links_custom_alias ON links(custom_alias);
CREATE INDEX IF NOT EXISTS idx_click_events_link_id ON click_events(link_id);
CREATE INDEX IF NOT EXISTS idx_click_events_clicked_at ON click_events(clicked_at DESC);
