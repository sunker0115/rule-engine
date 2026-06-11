-- metric_definition 三个 ENUM 列改 VARCHAR：ENUM 每加值需 ALTER 且与 app 双重定义，改 VARCHAR 后允许值校验上移 app 层。
ALTER TABLE metric_definition
  MODIFY COLUMN data_type   VARCHAR(32) NOT NULL,
  MODIFY COLUMN source_type VARCHAR(32) NOT NULL,
  MODIFY COLUMN status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
