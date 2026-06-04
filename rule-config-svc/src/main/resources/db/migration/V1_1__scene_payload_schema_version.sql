-- D13 演进基础设施：为 scene 表添加 payload_schema_version，新增 schema 历史快照表

ALTER TABLE scene
  ADD COLUMN payload_schema_version INT NOT NULL DEFAULT 1
    COMMENT 'payloadSchema 当前版本号，初始为 1，每次更新自增';

CREATE TABLE IF NOT EXISTS scene_payload_schema_history (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id    BIGINT       NOT NULL COMMENT '所属 scene.id',
  version     INT          NOT NULL COMMENT '该快照对应的版本号（变更前的版本）',
  schema_json JSON         NOT NULL COMMENT '历史 payloadSchema JSON 数组快照',
  created_by  VARCHAR(64)  COMMENT '触发变更的操作人',
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_ver (scene_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Scene payloadSchema 历史版本快照（D13 演进基础设施）';
