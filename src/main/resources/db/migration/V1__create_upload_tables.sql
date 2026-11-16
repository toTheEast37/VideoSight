CREATE TABLE upload_session (
    upload_id VARCHAR(36) NOT NULL COMMENT '后端生成的上传会话编号',
    request_key VARCHAR(36) NOT NULL COMMENT '同一次初始化意图复用的请求幂等键',
    original_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    file_fingerprint CHAR(64) NOT NULL COMMENT 'SHA256_CHUNK_LIST_V1指纹，不是整文件SHA256',
    content_type VARCHAR(127) NOT NULL,
    status VARCHAR(16) NOT NULL,
    cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE COMMENT '已完成资产仍待清理临时分片',
    expires_at DATETIME(3) NOT NULL COMMENT '未完成会话续传截止时间，UTC',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (upload_id),
    CONSTRAINT uk_upload_request UNIQUE (request_key),
    CONSTRAINT ck_upload_file_size CHECK (file_size > 0),
    CONSTRAINT ck_upload_chunk_size CHECK (chunk_size > 0),
    CONSTRAINT ck_upload_total_chunks CHECK (total_chunks > 0),
    CONSTRAINT ck_upload_status CHECK (status IN ('INITIALIZING', 'UPLOADING', 'FINALIZING', 'COMPLETED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_upload_expiry ON upload_session (status, expires_at, upload_id);
CREATE INDEX idx_upload_cleanup ON upload_session (cleanup_pending, status, updated_at, upload_id);

CREATE TABLE video_asset (
    asset_id BIGINT NOT NULL AUTO_INCREMENT,
    upload_id VARCHAR(36) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL COMMENT '服务端计算的最终视频真实SHA256',
    bucket VARCHAR(63) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (asset_id),
    CONSTRAINT uk_asset_upload UNIQUE (upload_id),
    CONSTRAINT fk_asset_upload FOREIGN KEY (upload_id) REFERENCES upload_session (upload_id),
    CONSTRAINT ck_asset_file_size CHECK (file_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_asset_created ON video_asset (created_at, asset_id);
