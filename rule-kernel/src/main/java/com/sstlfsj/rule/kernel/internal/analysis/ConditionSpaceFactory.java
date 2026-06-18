package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * 把单个叶子 {@link ConditionNode} 翻译为它在所属维度（metricCode+valueRef）上的取值空间，供规则集静态分析使用。
 *
 * <p>仅对取值空间可被 Task-1 {@link ConditionSpace} 精确表达的算子（GT/GTE/LT/LTE/EQ/IN/BETWEEN/DATE_BEFORE/DATE_AFTER
 * 的字面量形态）给出精确区间或点集；其余算子（含集合否定 NEQ/NOT_IN/NOT_BETWEEN、字符串/正则、时间窗口、SPI 自定义）
 * 以及参数缺失 / 类型不符 / 动态操作数（$now/$today），一律保守降级为 {@link ConditionSpace#unknown(String)}。
 */
public final class ConditionSpaceFactory {

    private ConditionSpaceFactory() {}

    /**
     * 求该叶子条件在其维度上的取值空间。
     *
     * @param node 叶子条件节点（conditionType + params 描述算子与操作数）
     * @return 满足该条件的取值空间；无法静态精确表达时为 {@link ConditionSpace#unknown(String)}
     */
    public static ConditionSpace from(ConditionNode node) {
        return fromOperator(node.conditionType(), node.params());
    }

    /**
     * 求某算子在给定操作数参数下的取值空间。叶子条件与决策表单元格共用此核心映射。
     *
     * <p>决策表单元格 = (列算子, 单元格值)：调用方按算子约定构造 params（GT→{threshold}、
     * BETWEEN→{min,max}、IN→{values}），再调用本方法，避免重复区间映射逻辑。
     *
     * @param type   算子码（conditionType）
     * @param params 该算子的操作数参数（键见 {@link ConditionParams}）
     * @return 满足该条件的取值空间；无法静态精确表达时为 {@link ConditionSpace#unknown(String)}
     */
    public static ConditionSpace fromOperator(String type, Map<String, Object> params) {
        return switch (type) {
            case ConditionTypes.GT -> numericThreshold(params, ConditionSpace::gt, "GT");
            case ConditionTypes.GTE -> numericThreshold(params, ConditionSpace::gte, "GTE");
            case ConditionTypes.LT -> numericThreshold(params, ConditionSpace::lt, "LT");
            case ConditionTypes.LTE -> numericThreshold(params, ConditionSpace::lte, "LTE");
            case ConditionTypes.EQ -> eq(params);
            case ConditionTypes.IN -> in(params);
            case ConditionTypes.BETWEEN -> between(params);
            case ConditionTypes.DATE_BEFORE -> dateBound(params, true);
            case ConditionTypes.DATE_AFTER -> dateBound(params, false);
            // 以下算子的取值空间 v1 不建模（集合否定无补集表达、字符串/正则/时间窗口非区间语义、SPI 开放集）
            default -> ConditionSpace.unknown(type + " 暂不建模(v1)");
        };
    }

    private static ConditionSpace numericThreshold(Map<String, Object> params,
                                                   DoubleFunction<ConditionSpace> build,
                                                   String op) {
        Double d = toDouble(params.get(ConditionParams.THRESHOLD));
        return d == null ? ConditionSpace.unknown(op + " threshold 非数值字面量") : build.apply(d);
    }

    private static ConditionSpace eq(Map<String, Object> params) {
        Object v = params.get(ConditionParams.THRESHOLD);
        return v == null ? ConditionSpace.unknown("EQ 缺少 threshold") : ConditionSpace.eq(v);
    }

    private static ConditionSpace in(Map<String, Object> params) {
        if (!(params.get(ConditionParams.VALUES) instanceof Collection<?> values)) {
            return ConditionSpace.unknown("IN values 非集合字面量");
        }
        if (values.isEmpty()) {
            return ConditionSpace.unknown("IN values 为空");
        }
        return ConditionSpace.in(new LinkedHashSet<Object>(values));
    }

    private static ConditionSpace between(Map<String, Object> params) {
        Double lo = toDouble(params.get(ConditionParams.MIN));
        Double hi = toDouble(params.get(ConditionParams.MAX));
        if (lo == null || hi == null) {
            return ConditionSpace.unknown("BETWEEN min/max 非数值字面量");
        }
        return ConditionSpace.between(lo, hi);
    }

    /** DATE_BEFORE → lt(epochMillis)；DATE_AFTER → gt(epochMillis)。$now/$today 为动态操作数、非日期字面量均降级。 */
    private static ConditionSpace dateBound(Map<String, Object> params, boolean before) {
        Long epoch = toEpochMillis(params.get(ConditionParams.THRESHOLD));
        if (epoch == null) {
            return ConditionSpace.unknown((before ? "DATE_BEFORE" : "DATE_AFTER") + " 操作数非日期字面量");
        }
        double e = epoch;
        return before ? ConditionSpace.lt(e) : ConditionSpace.gt(e);
    }

    /** 数值字面量强制为 double：Number 直取，String 解析；非有限 / 不可解析返回 null。 */
    private static Double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 日期字面量 → epoch 毫秒（UTC）：依次尝试 OffsetDateTime / LocalDateTime / LocalDate（裸日期按 UTC 当日 0 点）。
     * 动态占位符（$now/$today）与不可解析串返回 null（交由调用方降级）。
     */
    private static Long toEpochMillis(Object v) {
        if (v instanceof Instant i) {
            return i.toEpochMilli();
        }
        if (!(v instanceof String s)) {
            return null;
        }
        if (s.startsWith("$")) {
            return null; // $now / $today 等动态操作数无法静态求值
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {
            // 继续尝试更宽松的字面量形态
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignore) {
            // 继续尝试裸日期
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {
            return null;
        }
    }
}
