-- D49 拆分渠道(source) 与 模式(mode)：
-- source 由 PUSH/PULL/REPLAY 改为渠道枚举（HTTP/MQ/JOB/SDK/REPLAY），新增 mode 列存评估模式。
-- greenfield 无生产数据，空表直接 MODIFY。
ALTER TABLE evaluation_session
    MODIFY COLUMN source ENUM('HTTP','MQ','JOB','SDK','REPLAY') NOT NULL DEFAULT 'HTTP' COMMENT '事件渠道',
    ADD COLUMN mode ENUM('PUSH','PULL') NOT NULL DEFAULT 'PULL' COMMENT '评估模式' AFTER source;
