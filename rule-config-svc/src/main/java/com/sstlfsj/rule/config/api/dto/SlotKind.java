package com.sstlfsj.rule.config.api.dto;

/** Slot 种类，决定实例化验证与前端 picker；kind 隐含解析作用域，无需 scope 字段。 */
public enum SlotKind { VALUE, METRIC_REF, DECISION_REF, RULE_REF }
