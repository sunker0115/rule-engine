package com.sstlfsj.rule.kernel.internal.condition.time;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 解析段实现（B20 §6）：把原始操作数解析为类型化的 java.time 值，供纯比较策略使用。
 * 仅解析时间引用（$now/$today + ISO-8601 字面量），不做通用表达式（YAGNI）。
 * 解析失败一律返回 null，由调用方决定 false 还是 CONDITION_EVAL_ERROR。
 */
public final class PlaceholderResolver {

    private PlaceholderResolver() {}

    /**
     * DATE 语义解析：raw → LocalDate（纯日历日，与时区无关，仅 $today 用 zone 投影）。
     *
     * @param raw  原始值（String / LocalDate）
     * @param ctx  评估上下文（提供 now）
     * @param zone $today 投影所用时区
     * @return LocalDate；无法解析返回 null
     */
    public static LocalDate resolveDate(Object raw, EvalContext ctx, ZoneId zone) {
        if (raw instanceof LocalDate d) return d;
        if (raw instanceof String s) {
            if ("$today".equals(s)) return LocalDate.ofInstant(ctx.now(), zone);
            try { return LocalDate.parse(s); }
            catch (DateTimeParseException e) { return null; }
        }
        return null;
    }

    /**
     * DATETIME 语义解析：raw → Instant。
     * 顺序：$now → ctx.now()；$today → null（时间点不适用）；
     * 带 offset 字符串 → OffsetDateTime；裸日期时间 → LocalDateTime+zone；裸日期 → 当日 00:00+zone。
     *
     * @param raw  原始值（String / Instant）
     * @param ctx  评估上下文（提供 now）
     * @param zone 裸日期/裸日期时间补全所用时区
     * @return Instant；无法解析返回 null
     */
    public static Instant resolveDateTime(Object raw, EvalContext ctx, ZoneId zone) {
        if (raw instanceof Instant i) return i;
        if (raw instanceof String s) {
            if ("$now".equals(s)) return ctx.now();
            if ("$today".equals(s)) return null;
            try { return OffsetDateTime.parse(s).toInstant(); } catch (DateTimeParseException ignore) { }
            try { return LocalDateTime.parse(s).atZone(zone).toInstant(); } catch (DateTimeParseException ignore) { }
            try { return LocalDate.parse(s).atStartOfDay(zone).toInstant(); } catch (DateTimeParseException ignore) { }
            return null;
        }
        return null;
    }

    /**
     * 比较算子解析段调度：DATE→LocalDate，DATETIME→Instant，其余 dataType→原样直通（恒等段）。
     *
     * @param dataType 冻结后的 dataType
     * @param raw      原始操作数
     * @param ctx      评估上下文
     * @param zone     解析时区
     * @return 类型化值或直通值；DATE/DATETIME 解析失败返回 null
     */
    public static Object resolveTyped(String dataType, Object raw, EvalContext ctx, ZoneId zone) {
        if (DataType.DATE.tag().equals(dataType)) return resolveDate(raw, ctx, zone);
        if (DataType.DATETIME.tag().equals(dataType)) return resolveDateTime(raw, ctx, zone);
        return raw;
    }
}
