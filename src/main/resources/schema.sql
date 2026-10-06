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

CREATE TABLE IF NOT EXISTS workflow_cancellations (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL UNIQUE,
    cancelled_by VARCHAR(255) NOT NULL,
    reason VARCHAR(2048),
    cancelled_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cancellations_workflow_id ON workflow_cancellations(workflow_id);

CREATE TABLE IF NOT EXISTS workflow_runs (
    id VARCHAR(36) PRIMARY KEY,
    original_requirement CLOB NOT NULL,
    requirement CLOB NOT NULL,
    repository_path VARCHAR(1024),
    scenario VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(64) NOT NULL,
    plan_hash VARCHAR(64),
    acceptance_criteria_json CLOB,
    assumptions_json CLOB,
    unanswered_questions_json CLOB,
    tasks_json CLOB,
    specialist_invocations_json CLOB,
    repository_evidence_json CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workflow_runs_status ON workflow_runs(status);

CREATE TABLE IF NOT EXISTS workflow_events (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    description VARCHAR(2048) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workflow_events_workflow_id ON workflow_events(workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_events_timestamp ON workflow_events(timestamp ASC);

CREATE TABLE IF NOT EXISTS workflow_agent_decisions (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    agent_type VARCHAR(64) NOT NULL,
    decision VARCHAR(256) NOT NULL,
    rationale VARCHAR(2048),
    metadata_json CLOB,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_decisions_workflow_id ON workflow_agent_decisions(workflow_id);
CREATE INDEX IF NOT EXISTS idx_agent_decisions_timestamp ON workflow_agent_decisions(timestamp ASC);

