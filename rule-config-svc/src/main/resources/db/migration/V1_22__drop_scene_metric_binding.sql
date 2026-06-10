-- V1_22__drop_scene_metric_binding.sql
-- 砍 metric binding 白名单:metric 在 tenant 级对所有 scene 可用(配置闭环 B 轮决策二)
DROP TABLE IF EXISTS scene_metric_binding;
