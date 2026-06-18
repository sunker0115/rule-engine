CREATE TABLE IF NOT EXISTS connector_definition (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  connector_code  VARCHAR(128) NOT NULL COMMENT 'connectorCode，租户内唯一',
  name            VARCHAR(128) NOT NULL,
  descriptor      JSON         NOT NULL COMMENT '声明式连接器描述符（request/response/auth/resilience/errorMapping）',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/DISABLED',
  created_by      VARCHAR(64),
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64),
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_connector (tenant_id, connector_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='连接器定义';
