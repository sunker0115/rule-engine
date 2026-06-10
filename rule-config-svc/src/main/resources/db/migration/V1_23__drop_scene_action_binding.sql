-- V1_23__drop_scene_action_binding.sql
-- 砍 scene_action_binding 整表:action 触发源唯一=decision、与 scene 无关(配置闭环 B 轮决策三);D50 写 API 作废
DROP TABLE IF EXISTS scene_action_binding;
