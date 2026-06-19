-- 通用调度任务框架:替换 job_definition / job_execution(评估耦合聚合)。greenfield 无生产数据需保留。
CREATE TABLE IF NOT EXISTS scheduled_task (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(64)  NOT NULL COMMENT '租户内唯一',
  name        VARCHAR(128) NOT NULL,
  task_type   VARCHAR(32)  NOT NULL COMMENT 'TaskType: TRIGGER / OUTCOME_INGESTION',
  cron        VARCHAR(128) NOT NULL COMMENT 'Spring 6 段 cron;seed 初值,XXL admin 运行时权威',
  config      JSON         NOT NULL COMMENT 'typed TaskConfig(多态 kind 判别)',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  created_by  VARCHAR(64),
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64),
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用调度任务定义';

CREATE TABLE IF NOT EXISTS scheduled_task_execution (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  scheduled_task_id BIGINT      NOT NULL,
  tenant_id        BIGINT       NOT NULL,
  trigger_at       TIMESTAMP(3) NOT NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/PARTIAL_FAIL/FAILED',
  processed_count  INT          NOT NULL DEFAULT 0 COMMENT 'TRIGGER:主体数 / INGESTION:标签行数',
  success_count    INT          NOT NULL DEFAULT 0,
  error_count      INT          NOT NULL DEFAULT 0,
  error_summary    TEXT,
  finished_at      TIMESTAMP(3) NULL,
  KEY idx_task_trigger (scheduled_task_id, trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度任务执行记录';

DROP TABLE IF EXISTS job_execution;
DROP TABLE IF EXISTS job_definition;
