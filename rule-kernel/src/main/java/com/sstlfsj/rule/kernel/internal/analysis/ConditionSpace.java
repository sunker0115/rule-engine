package com.sstlfsj.rule.kernel.internal.analysis;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单维度（一个 metric+valueRef 维度）上某条件的取值空间，用于规则集静态分析。
 *
 * <p>通过 {@link #overlaps(ConditionSpace)}（是否存在公共点）、{@link #subsumes(ConditionSpace)}
 * （是否完全包含）、{@link #meet(ConditionSpace)}（交集空间）三种运算推断条件间的相交 / 包含 / 互斥关系。
 * 无法静态精确判定时返回 {@link Tri#UNKNOWN} 保守降级。
 *
 * <p>仅建模正向取值：{@link PointSet} 表示 EQ / IN 的有限点集，NEQ / NOT_IN 的否定不在 v1 范围内。
 */
public sealed interface ConditionSpace
        permits ConditionSpace.NumericRange, ConditionSpace.PointSet, ConditionSpace.Any,
        ConditionSpace.Empty, ConditionSpace.Unknown {

    /** 与另一空间是否存在至少一个公共点。 */
    Tri overlaps(ConditionSpace o);

    /** 本空间是否完全包含另一空间（this ⊇ o，o 的每个点都在 this 内）。 */
    Tri subsumes(ConditionSpace o);

    /** 与另一空间的交集；不相交返回 {@link Empty}，任一为 {@link Unknown} 返回 {@link Unknown}。 */
    ConditionSpace meet(ConditionSpace o);

    /** 是否为空集（恒不可满足）。 */
    boolean isEmpty();

    /** 是否为无法静态判定的未知空间。 */
    boolean isUnknown();

    // ---------- 静态工厂 ----------

    /** 全集：包含该维度上所有取值。 */
    static ConditionSpace any() {
        return Any.INSTANCE;
    }

    /** 空集：恒不可满足。 */
    static ConditionSpace empty() {
        return Empty.INSTANCE;
    }

    /** 未知空间：取值不可静态求解（如正则）。 */
    static ConditionSpace unknown(String reason) {
        return new Unknown(reason);
    }

    /** 闭区间 [lo, hi]。 */
    static ConditionSpace between(double lo, double hi) {
        return range(lo, true, hi, true);
    }

    /** 开区间 (v, +inf)。 */
    static ConditionSpace gt(double v) {
        return range(v, false, Double.POSITIVE_INFINITY, false);
    }

    /** 闭下界区间 [v, +inf)。 */
    static ConditionSpace gte(double v) {
        return range(v, true, Double.POSITIVE_INFINITY, false);
    }

    /** 开区间 (-inf, v)。 */
    static ConditionSpace lt(double v) {
        return range(Double.NEGATIVE_INFINITY, false, v, false);
    }

    /** 闭上界区间 (-inf, v]。 */
    static ConditionSpace lte(double v) {
        return range(Double.NEGATIVE_INFINITY, false, v, true);
    }

    /** 通用区间构造；端点开闭由 loInc / hiInc 指定。空区间归一化为 {@link Empty}。 */
    static ConditionSpace range(double lo, boolean loInc, double hi, boolean hiInc) {
        NumericRange r = new NumericRange(lo, loInc, hi, hiInc);
        return r.isEmpty() ? Empty.INSTANCE : r;
    }

    /** 单点集合 {v}，建模 EQ。 */
    static ConditionSpace eq(Object v) {
        return in(Set.of(v));
    }

    /** 有限点集，建模 IN。空集归一化为 {@link Empty}。 */
    static ConditionSpace in(Set<?> values) {
        if (values.isEmpty()) {
            return Empty.INSTANCE;
        }
        return new PointSet(new LinkedHashSet<>(values));
    }

    // ---------- 实现 ----------

    /**
     * 数值实区间，端点可开可闭，无界侧用 ±Infinity。
     *
     * @param lo    下界
     * @param loInc 下界是否闭合
     * @param hi    上界
     * @param hiInc 上界是否闭合
     */
    record NumericRange(double lo, boolean loInc, double hi, boolean hiInc) implements ConditionSpace {

        @Override
        public boolean isEmpty() {
            // lo>hi 必空；lo==hi 时只有两端都闭合（退化为单点）才非空
            if (lo > hi) {
                return true;
            }
            return lo == hi && !(loInc && hiInc);
        }

        @Override
        public boolean isUnknown() {
            return false;
        }

        /** 标量是否落在本区间内（考虑端点开闭）。 */
        boolean contains(double x) {
            boolean aboveLo = loInc ? x >= lo : x > lo;
            boolean belowHi = hiInc ? x <= hi : x < hi;
            return aboveLo && belowHi;
        }

        @Override
        public Tri overlaps(ConditionSpace o) {
            return switch (o) {
                case Empty ignored -> Tri.FALSE;
                case Unknown ignored -> Tri.UNKNOWN;
                case Any ignored -> Tri.TRUE;
                case NumericRange r -> rangesOverlap(this, r) ? Tri.TRUE : Tri.FALSE;
                case PointSet ps -> pointSetOverlapsRange(ps, this);
            };
        }

        @Override
        public Tri subsumes(ConditionSpace o) {
            return switch (o) {
                case Empty ignored -> Tri.TRUE; // 空集被任何空间包含
                case Unknown ignored -> Tri.UNKNOWN;
                case Any ignored -> Tri.FALSE; // 非全集不可能包含全集
                case NumericRange r -> rangeSubsumesRange(this, r) ? Tri.TRUE : Tri.FALSE;
                case PointSet ps -> rangeSubsumesPointSet(this, ps);
            };
        }

        @Override
        public ConditionSpace meet(ConditionSpace o) {
            return switch (o) {
                case Empty ignored -> Empty.INSTANCE;
                case Unknown u -> u;
                case Any ignored -> this;
                case NumericRange r -> rangeMeetRange(this, r);
                case PointSet ps -> ps.meet(this); // 点集 ∩ 区间，复用点集侧
            };
        }
    }

    /**
     * 有限点集，建模 EQ / IN 的允许取值。
     *
     * @param values 允许取值集合（非空，空集归一化为 {@link Empty}）
     */
    record PointSet(Set<?> values) implements ConditionSpace {

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public boolean isUnknown() {
            return false;
        }

        @Override
        public Tri overlaps(ConditionSpace o) {
            return switch (o) {
                case Empty ignored -> Tri.FALSE;
                case Unknown ignored -> Tri.UNKNOWN;
                case Any ignored -> Tri.TRUE;
                case PointSet other -> pointSetsOverlap(this, other);
                case NumericRange r -> pointSetOverlapsRange(this, r);
            };
        }

        @Override
        public Tri subsumes(ConditionSpace o) {
            return switch (o) {
                case Empty ignored -> Tri.TRUE;
                case Unknown ignored -> Tri.UNKNOWN;
                case Any ignored -> Tri.FALSE;
                case PointSet other -> values.containsAll(other.values) ? Tri.TRUE : Tri.FALSE;
                case NumericRange r -> pointSetSubsumesRange(this, r);
            };
        }

        @Override
        public ConditionSpace meet(ConditionSpace o) {
            return switch (o) {
                case Empty ignored -> Empty.INSTANCE;
                case Unknown u -> u;
                case Any ignored -> this;
                case PointSet other -> {
                    Set<Object> inter = new LinkedHashSet<>(values);
                    inter.retainAll(other.values);
                    yield inter.isEmpty() ? Empty.INSTANCE : new PointSet(inter);
                }
                case NumericRange r -> pointSetMeetRange(this, r);
            };
        }
    }

    /** 全集：包含该维度上所有取值。 */
    record Any() implements ConditionSpace {
        static final Any INSTANCE = new Any();

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean isUnknown() {
            return false;
        }

        @Override
        public Tri overlaps(ConditionSpace o) {
            // 全集与任何非空、非未知空间都相交
            if (o.isUnknown()) {
                return Tri.UNKNOWN;
            }
            return o.isEmpty() ? Tri.FALSE : Tri.TRUE;
        }

        @Override
        public Tri subsumes(ConditionSpace o) {
            return o.isUnknown() ? Tri.UNKNOWN : Tri.TRUE;
        }

        @Override
        public ConditionSpace meet(ConditionSpace o) {
            return o;
        }
    }

    /** 空集：恒不可满足。 */
    record Empty() implements ConditionSpace {
        static final Empty INSTANCE = new Empty();

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public boolean isUnknown() {
            return false;
        }

        @Override
        public Tri overlaps(ConditionSpace o) {
            return Tri.FALSE;
        }

        @Override
        public Tri subsumes(ConditionSpace o) {
            // 空集只包含空集；遇 Unknown 无法判定
            if (o.isUnknown()) {
                return Tri.UNKNOWN;
            }
            return o.isEmpty() ? Tri.TRUE : Tri.FALSE;
        }

        @Override
        public ConditionSpace meet(ConditionSpace o) {
            return this;
        }
    }

    /**
     * 未知空间：取值不可静态求解，所有运算保守降级为 {@link Tri#UNKNOWN}。
     *
     * @param reason 降级原因（如 "regex"），仅用于诊断
     */
    record Unknown(String reason) implements ConditionSpace {

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean isUnknown() {
            return true;
        }

        @Override
        public Tri overlaps(ConditionSpace o) {
            return Tri.UNKNOWN;
        }

        @Override
        public Tri subsumes(ConditionSpace o) {
            return Tri.UNKNOWN;
        }

        @Override
        public ConditionSpace meet(ConditionSpace o) {
            // 空集 ∩ 未知 = 空集（满足交集交换律）；否则仍不可判定
            return o.isEmpty() ? o : this;
        }
    }

    // ---------- 跨类型辅助（包私有静态方法） ----------

    private static boolean rangesOverlap(NumericRange a, NumericRange b) {
        // 取较大下界与较小上界，比较是否构成非空区间
        double lo = Math.max(a.lo(), b.lo());
        double hi = Math.min(a.hi(), b.hi());
        if (lo > hi) {
            return false;
        }
        if (lo == hi) {
            // 边界点重合时，须两侧在该点都闭合才算共享
            boolean loInc = (a.lo() < lo || a.loInc()) && (b.lo() < lo || b.loInc());
            boolean hiInc = (a.hi() > hi || a.hiInc()) && (b.hi() > hi || b.hiInc());
            return loInc && hiInc;
        }
        return true;
    }

    private static boolean rangeSubsumesRange(NumericRange a, NumericRange b) {
        // a ⊇ b：a 的下界不高于 b 且上界不低于 b，端点开闭须兼容
        boolean loOk = a.lo() < b.lo() || (a.lo() == b.lo() && (a.loInc() || !b.loInc()));
        boolean hiOk = a.hi() > b.hi() || (a.hi() == b.hi() && (a.hiInc() || !b.hiInc()));
        return loOk && hiOk;
    }

    private static ConditionSpace rangeMeetRange(NumericRange a, NumericRange b) {
        double lo;
        boolean loInc;
        if (a.lo() > b.lo()) {
            lo = a.lo();
            loInc = a.loInc();
        } else if (a.lo() < b.lo()) {
            lo = b.lo();
            loInc = b.loInc();
        } else {
            lo = a.lo();
            loInc = a.loInc() && b.loInc();
        }
        double hi;
        boolean hiInc;
        if (a.hi() < b.hi()) {
            hi = a.hi();
            hiInc = a.hiInc();
        } else if (a.hi() > b.hi()) {
            hi = b.hi();
            hiInc = b.hiInc();
        } else {
            hi = a.hi();
            hiInc = a.hiInc() && b.hiInc();
        }
        return range(lo, loInc, hi, hiInc);
    }

    /** 把任意值强制转为 double；非数值返回 null（含无法解析的字符串）。 */
    private static Double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                // NaN / Infinity / 溢出解析为非有限值 → 视为非数值,降级 UNKNOWN
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Tri pointSetsOverlap(PointSet a, PointSet b) {
        for (Object v : a.values()) {
            if (b.values().contains(v)) {
                return Tri.TRUE;
            }
        }
        return Tri.FALSE;
    }

    /** 点集 vs 数值区间的相交判定：非数值点遇区间即无法判定。 */
    private static Tri pointSetOverlapsRange(PointSet ps, NumericRange r) {
        boolean sawUnknown = false;
        for (Object v : ps.values()) {
            Double d = toDouble(v);
            if (d == null) {
                sawUnknown = true; // 非数值点无法与数值区间比较
                continue;
            }
            if (r.contains(d)) {
                return Tri.TRUE;
            }
        }
        return sawUnknown ? Tri.UNKNOWN : Tri.FALSE;
    }

    /** 区间是否包含整个点集：任一点非数值则无法判定，任一数值点不在区间则不包含。 */
    private static Tri rangeSubsumesPointSet(NumericRange r, PointSet ps) {
        for (Object v : ps.values()) {
            Double d = toDouble(v);
            if (d == null) {
                return Tri.UNKNOWN;
            }
            if (!r.contains(d)) {
                return Tri.FALSE;
            }
        }
        return Tri.TRUE;
    }

    /** 有限点集是否包含整个数值区间：仅当区间退化为它含有的单个闭点时成立，否则 FALSE。 */
    private static Tri pointSetSubsumesRange(PointSet ps, NumericRange r) {
        // 退化单点区间 [x,x]：当且仅当点集含 x 时被包含
        if (r.lo() == r.hi() && r.loInc() && r.hiInc()) {
            for (Object v : ps.values()) {
                Double d = toDouble(v);
                if (d != null && d == r.lo()) {
                    return Tri.TRUE;
                }
            }
            return Tri.FALSE;
        }
        // 非退化区间含无穷多点，有限点集无法包含
        return Tri.FALSE;
    }

    /** 点集 ∩ 数值区间：保留落在区间内的数值点；含非数值点则无法判定。 */
    private static ConditionSpace pointSetMeetRange(PointSet ps, NumericRange r) {
        Set<Object> kept = new LinkedHashSet<>();
        for (Object v : ps.values()) {
            Double d = toDouble(v);
            if (d == null) {
                return new Unknown("non-numeric point vs numeric range");
            }
            if (r.contains(d)) {
                kept.add(v);
            }
        }
        return kept.isEmpty() ? Empty.INSTANCE : new PointSet(kept);
    }
}
