-- rollout 列为 D6 初版灰度快照遗留，ROLLOUT 改由 pre_gates 承载后该列只写不读，删除。
ALTER TABLE rule_version DROP COLUMN rollout;
