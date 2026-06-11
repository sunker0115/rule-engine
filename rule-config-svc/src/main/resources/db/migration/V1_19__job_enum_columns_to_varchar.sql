-- job_definition / job_execution 的 status ENUM → VARCHAR（取值真相源上移 app 层 Java enum）。
ALTER TABLE job_definition
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE job_execution
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'RUNNING';