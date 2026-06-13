-- D71 读时脱敏：metric 定义级敏感标志（租户级共享 D54）。trace 展示出口据此遮蔽该 metric 值。
-- 列名 `sensitive` 是 MySQL 8 保留字，DDL/DML 均须反引号包裹（实体侧 @TableField 同步带反引号）。
ALTER TABLE metric_definition
    ADD COLUMN `sensitive` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否敏感 metric：1=trace 展示出口读时脱敏（D71）' AFTER allow_provided;
