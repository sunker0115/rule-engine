-- 移除 scene_action_binding.rate_limit_override:action 级频控无消费方且设计冗余
-- (Job 注入端已控速率;真需限流应在 ActionHandler 内部或上分布式,不在通用引擎层)
ALTER TABLE scene_action_binding DROP COLUMN rate_limit_override;
