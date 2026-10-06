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

CREATE TABLE IF NOT EXISTS workflow_clarifications (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    clarification_text CLOB NOT NULL,
    submitted_by VARCHAR(255) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS workflow_approvals (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL UNIQUE,
    decision VARCHAR(32) NOT NULL,
    approver VARCHAR(255) NOT NULL,
    plan_hash VARCHAR(64) NOT NULL,
    comments VARCHAR(2048),
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_clarifications_workflow_id ON workflow_clarifications(workflow_id);
CREATE INDEX IF NOT EXISTS idx_approvals_workflow_id ON workflow_approvals(workflow_id);
