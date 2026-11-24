CREATE TABLE video_search_vector (
    asset_id BIGINT NOT NULL,
    content_key CHAR(64) NOT NULL COMMENT '向量模型、接口及片段输入的摘要',
    embedding_json LONGTEXT NOT NULL,
    PRIMARY KEY (asset_id, content_key),
    CONSTRAINT fk_search_vector_asset FOREIGN KEY (asset_id) REFERENCES video_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
