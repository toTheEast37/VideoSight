CREATE TABLE parse_task (
    task_id VARCHAR(36) NOT NULL,
    asset_id BIGINT NOT NULL,
    profile_version VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    duration_ms BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT uk_parse_asset_profile UNIQUE (asset_id, profile_version),
    CONSTRAINT fk_parse_asset FOREIGN KEY (asset_id) REFERENCES video_asset (asset_id),
    CONSTRAINT ck_parse_task_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE parse_request (
    request_key VARCHAR(128) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (request_key),
    CONSTRAINT fk_parse_request_task FOREIGN KEY (task_id) REFERENCES parse_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE parse_unit (
    task_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    unit_index INT NOT NULL,
    generation INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    input_key VARCHAR(1024) NULL,
    start_ms BIGINT NOT NULL DEFAULT 0,
    end_ms BIGINT NOT NULL DEFAULT 0,
    result_text LONGTEXT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    lease_token VARCHAR(128) NULL,
    lease_until DATETIME(3) NULL,
    next_run_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (task_id, kind, unit_index),
    CONSTRAINT fk_parse_unit_task FOREIGN KEY (task_id) REFERENCES parse_task (task_id),
    CONSTRAINT ck_parse_unit_kind CHECK (kind IN ('PREPARE', 'ASR', 'OCR')),
    CONSTRAINT ck_parse_unit_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED', 'UNCERTAIN')),
    CONSTRAINT ck_parse_unit_counters CHECK (unit_index >= 0 AND generation >= 0 AND attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_parse_unit_recovery ON parse_unit (status, updated_at);

CREATE TABLE parse_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    unit_index INT NOT NULL,
    generation INT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    sent_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_parse_outbox_step UNIQUE (task_id, kind, unit_index, generation),
    CONSTRAINT fk_parse_outbox_task FOREIGN KEY (task_id) REFERENCES parse_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_parse_outbox_pending ON parse_outbox (sent_at, next_attempt_at, id);
