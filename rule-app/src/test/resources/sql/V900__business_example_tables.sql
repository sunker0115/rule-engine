-- SQL_AGGREGATE 取数场景用的业务表（模拟外部业务数据源）
-- 这个表与规则引擎自身的 migration 隔离，由 Flyway 的 classpath:sql 路径加载

CREATE TABLE IF NOT EXISTS orders (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL COMMENT '用户 ID',
    amount     DECIMAL(12,2) NOT NULL COMMENT '订单金额',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表(业务示例)';
