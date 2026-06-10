package com.sstlfsj.rule.eval.internal.validate;

import com.sstlfsj.rule.kernel.api.model.PayloadDependency;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 评估期 payload 入参校验:据候选快照的 payload 依赖清单,校验请求 payload 必填全到 + 基础类型匹配。
 * 多塞的、无规则引用的字段忽略(当额外上下文)。违约抛 IllegalArgumentException
 * (message 前缀 MISSING_REQUIRED_INPUT / INPUT_TYPE_MISMATCH),经 GlobalExceptionHandler → HTTP 400。
 */
public final class PayloadInputValidator {
    private PayloadInputValidator() {}

    /**
     * @param deps    候选快照的 payload 依赖并集(同名去重后)
     * @param payload 请求事件 payload(可能为 null)
     * @throws IllegalArgumentException 必填缺失或类型不符
     */
    public static void validate(List<PayloadDependency> deps, Map<String, Object> payload) {
        Map<String, Object> p = payload == null ? Map.of() : payload;
        for (PayloadDependency d : deps) {
            if (!p.containsKey(d.name())) {
                if (d.required()) {
                    throw new IllegalArgumentException("MISSING_REQUIRED_INPUT: 缺少必填字段 " + d.name());
                }
                continue;
            }
            Object value = p.get(d.name());
            if (value != null && !typeMatches(d.dataType(), value)) {
                throw new IllegalArgumentException(
                        "INPUT_TYPE_MISMATCH: 字段 " + d.name() + " 类型不符,期望 " + d.dataType()
                        + ",实际 " + value.getClass().getSimpleName());
            }
        }
    }

    /** 基础类型匹配;UNKNOWN / 非基础类型放行。 */
    private static boolean typeMatches(String dataTypeTag, Object value) {
        return switch (dataTypeTag) {
            case "DECIMAL", "LONG", "DOUBLE" -> value instanceof Number;
            case "STRING" -> value instanceof CharSequence;
            case "BOOLEAN" -> value instanceof Boolean;
            case "LIST" -> value instanceof Collection<?>;
            default -> true;
        };
    }
}
