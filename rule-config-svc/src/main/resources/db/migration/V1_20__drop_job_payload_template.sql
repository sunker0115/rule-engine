-- 移除 job_definition.payload_template（D49 遗留死列）：无写入方、无读取方，
-- payload 由 @RuleJob 方法返回的 JobTarget.payload 直接携带，列与实体字段一并收口。
ALTER TABLE job_definition DROP COLUMN payload_template;
