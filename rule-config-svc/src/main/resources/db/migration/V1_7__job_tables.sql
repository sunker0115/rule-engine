-- D11 Job 模式：JobDefinition（定义）+ JobExecution（执行记录）
-- Job 仅对 PUSH / HYBRID Scene 开放，作为 Trigger 适配器合成 RuleEvent 注入标准评估链路。
-- 注：scene 以 scene_code 关联（与 RuleEvent.sceneCode / SceneService 按 code 查询口径一致），不持 scene_id。

CREATE TABLE IF NOT EXISTS job_definition (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL COMMENT '所属租户 id',
  scene_code       VARCHAR(64)  NOT NULL COMMENT '绑定的 Scene code（仅 PUSH/HYBRID）',
  code             VARCHAR(64)  NOT NULL COMMENT 'Job 编码，租户+场景内唯一',
  name             VARCHAR(128) NOT NULL,
  cron_expression  VARCHAR(128) NOT NULL COMMENT 'Spring 6 段 cron（秒 分 时 日 月 周）',
  subject_query    JSON         NOT NULL COMMENT '主体查询配置，如 {"type":"SQL","sql":"SELECT ... AS subjectId ..."}',
  event_type       VARCHAR(64)  NOT NULL COMMENT '合成 RuleEvent 使用的 eventType',
  payload_template JSON         COMMENT 'payload 模板，占位符按主体行同名字段填充',
  status           ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_by       VARCHAR(64)  COMMENT '创建人',
  created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by       VARCHAR(64)  COMMENT '最近修改人',
  updated_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_scene_code (tenant_id, scene_code, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Job 定义（D11 Trigger 适配器）';

CREATE TABLE IF NOT EXISTS job_execution (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_definition_id BIGINT       NOT NULL COMMENT '归属 Job',
  tenant_id         BIGINT       NOT NULL,
  trigger_at        TIMESTAMP(3) NOT NULL COMMENT '调度器触发时间',
  status            ENUM('RUNNING','SUCCESS','PARTIAL_FAIL','FAILED') NOT NULL DEFAULT 'RUNNING',
  subject_count     INT          NOT NULL DEFAULT 0 COMMENT '查询到的主体总数',
  success_count     INT          NOT NULL DEFAULT 0 COMMENT '成功注入评估链路的主体数',
  error_count       INT          NOT NULL DEFAULT 0,
  error_summary     TEXT         COMMENT '错误明细摘要',
  finished_at       TIMESTAMP(3) NULL COMMENT '完成时间',
  KEY idx_job_trigger (job_definition_id, trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Job 执行记录';