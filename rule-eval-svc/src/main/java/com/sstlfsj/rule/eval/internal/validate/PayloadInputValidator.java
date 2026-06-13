package com.sstlfsj.rule.eval.internal.validate;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评估期 payload 入参校验:据候选快照的 payload 依赖清单,校验请求 payload 必填全到 + 基础类型匹配
 * + 冻结的 enum/min/max/pattern 约束。pattern 用 RE2J(线性时间,防 ReDoS)+ 编译缓存(防每事件重编译)。
 * 多塞的、无规则引用的字段忽略(当额外上下文)。违约抛 IllegalArgumentException
 * (message 前缀 MISSING_REQUIRED_INPUT / INPUT_TYPE_MISMATCH / INPUT_ENUM_VIOLATION /
 * INPUT_RANGE_VIOLATION / INPUT_PATTERN_VIOLATION),经 GlobalExceptionHandler → HTTP 400。
 */
public final class PayloadInputValidator {
    private PayloadInputValidator() {}

    /** pattern → 编译结果缓存(Optional.empty 表示非法正则,放行不拦)。 */
    private static final ConcurrentHashMap<String, Optional<Pattern>> PATTERN_CACHE = new ConcurrentHashMap<>();

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
            if (value == null) {
                continue;
            }
            if (!typeMatches(d.dataType(), value)) {
                throw new IllegalArgumentException(
                        "INPUT_TYPE_MISMATCH: 字段 " + d.name() + " 类型不符,期望 " + d.dataType()
                        + ",实际 " + value.getClass().getSimpleName());
            }
            if (d.enumValues() != null && !d.enumValues().isEmpty() && !d.enumValues().contains(value)) {
                throw new IllegalArgumentException(
                        "INPUT_ENUM_VIOLATION: 字段 " + d.name() + " 值不在枚举 " + d.enumValues() + " 内");
            }
            if (value instanceof Number num) {
                double dv = num.doubleValue();
                if (d.minimum() != null && dv < d.minimum()) {
                    throw new IllegalArgumentException("INPUT_RANGE_VIOLATION: 字段 " + d.name() + " 小于下界 " + d.minimum());
                }
                if (d.maximum() != null && dv > d.maximum()) {
                    throw new IllegalArgumentException("INPUT_RANGE_VIOLATION: 字段 " + d.name() + " 超过上界 " + d.maximum());
                }
            }
            if (d.pattern() != null && value instanceof CharSequence cs) {
                Optional<Pattern> p2 = PATTERN_CACHE.computeIfAbsent(d.pattern(), PayloadInputValidator::compileQuietly);
                if (p2.isPresent() && !p2.get().matcher(cs).matches()) {
                    throw new IllegalArgumentException("INPUT_PATTERN_VIOLATION: 字段 " + d.name() + " 不匹配 " + d.pattern());
                }
            }
        }
    }

    /** 编译正则,非法正则返回 empty(放行不拦,避免坏配置阻断评估)。 */
    private static Optional<Pattern> compileQuietly(String regex) {
        try {
            return Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            return Optional.empty();
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
