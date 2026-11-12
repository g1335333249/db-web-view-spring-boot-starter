-- Run on a shared MySQL or PostgreSQL audit database before setting db-web-view.audit-source.
-- Time columns store Unix milliseconds so pods in different time zones agree.
CREATE TABLE IF NOT EXISTS dbwv_audit_event (
    event_id VARCHAR(36) PRIMARY KEY,
    occurred_at BIGINT NOT NULL,
    action_name VARCHAR(32) NOT NULL,
    source_name VARCHAR(128),
    namespace_name VARCHAR(128),
    table_name VARCHAR(128),
    client_ip VARCHAR(64),
    result_kind VARCHAR(32),
    affected BIGINT,
    row_count BIGINT,
    elapsed_ms BIGINT,
    error_text VARCHAR(1000),
    sql_text TEXT
);
CREATE INDEX dbwv_audit_event_time_idx ON dbwv_audit_event (occurred_at);
CREATE TABLE IF NOT EXISTS dbwv_token_revocation (
    jti VARCHAR(43) PRIMARY KEY,
    expires_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS dbwv_login_failure (
    event_id VARCHAR(36) PRIMARY KEY,
    occurred_at BIGINT NOT NULL,
    client_ip VARCHAR(64) NOT NULL
);
CREATE INDEX dbwv_login_failure_ip_time_idx ON dbwv_login_failure (client_ip, occurred_at);
