package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * audit_log.target_type 取值：变更对象类型。
 *
 * <p>DB 落库值为小写下划线 code（{@link EnumValue} 标注的 {@code code} 字段），
 * 与大写 enum 名不一致，故由 MyBatis-Plus 按 code 而非 name 持久化。
 */
@Getter
@RequiredArgsConstructor
public enum AuditTargetType {
    RULE_DEFINITION("rule_definition"),
    RULE_VERSION("rule_version"),
    SCENE("scene"),
    METRIC_DEFINITION("metric_definition"),
    DECISION_DEFINITION("decision_definition"),
    CONNECTOR_DEFINITION("connector_definition"),
    JOB("job"),
    RULE_TEMPLATE("rule_template");

    @EnumValue
    private final String code;
}
