-- B20 §5.2：metric_definition.data_type ENUM 增加 DATE / DATETIME
ALTER TABLE metric_definition
  MODIFY COLUMN data_type
  ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST','DATE','DATETIME') NOT NULL;
