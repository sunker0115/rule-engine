-- B32 决策效果闭环：业务真实结果标签回灌表（关联 evaluation_session 的 event 维度）
CREATE TABLE IF NOT EXISTS decision_outcome (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT       NOT NULL,
  event_id      VARCHAR(128) NOT NULL COMMENT '业务事件 id，关联 evaluation_session(tenant_id,event_id)',
  outcome_label VARCHAR(64)  NOT NULL COMMENT '业务自定义结果标签，引擎不解释（如 FRAUD/NOT_FRAUD）',
  outcome_value DECIMAL(18,4) COMMENT '可选数值标签，如真实损失额',
  outcome_note  VARCHAR(512) COMMENT '可选备注',
  labeled_at    TIMESTAMP(3) NOT NULL COMMENT '业务真值确定时刻（非回灌落库时刻）',
  source        VARCHAR(64)  COMMENT '回灌方标识',
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_event (tenant_id, event_id),
  KEY idx_tenant_labeled (tenant_id, labeled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='决策结果标签回灌（B32）';
