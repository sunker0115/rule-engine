package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.ConflictFinding;
import com.sstlfsj.rule.kernel.api.analysis.DeadRuleFinding;
import com.sstlfsj.rule.kernel.api.analysis.OverlapFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 决策表行内分析（DMN 风格）：在<b>同一张决策表内部</b>，找出行对的输入相交、决策冲突与被掩盖的死行。
 *
 * <p>每行投影为一个 {@link RuleCube}：列 i 的取值空间由 {@code ConditionSpaceFactory.fromOperator(列算子, 单元格参数)}
 * 求得，维度键为 {@code 列.metricCode + "@" + 列.valueRef}；同一 metric 出现在多列时按 {@link ConditionSpace#meet} 取交。
 * 单元格为 {@code null}（通配）该列退化为全集 {@link ConditionSpace#any()}；单元格无法精确塑形为算子参数时该列降级
 * {@link ConditionSpace#unknown(String)}。
 *
 * <p>对同表内有序行对 (i&lt;j)：
 * <ul>
 *   <li>{@code cube_i.overlaps(cube_j) == TRUE} 且决策相同 → {@link Severity#INFO} 级 {@link OverlapFinding}；</li>
 *   <li>{@code cube_i.overlaps(cube_j) == TRUE} 且决策不同 → {@link Severity#WARN} 级 {@link ConflictFinding}；</li>
 *   <li>{@code cube_i.subsumes(cube_j) == TRUE} → 行 j 被在先行 i 完全覆盖、永不命中（死行，{@link Severity#WARN} 级
 *       {@link DeadRuleFinding}）——决策表为 FIRST_HIT 顺序匹配语义，在先行恒先胜出，故掩盖判定成立。</li>
 * </ul>
 * 任一关系为 {@code UNKNOWN} 一律跳过（零误报）。
 *
 * <p>loc 标识：行 r（0-based 下标）→ {@code ruleCode + "#row" + (r+1)}（1-based、人类可读）。
 *
 * <p><b>未来工作</b>：本 v1 不做表内输入空间完整性分析（DMN "missing rule" —— 整个输入域上的覆盖缺口），
 * 那是更复杂的独立分析，留待后续。
 */
public final class DecisionTableDetector {

    private DecisionTableDetector() {}

    /**
     * 决策表行内分析的三类发现汇总。
     *
     * @param overlaps  同决策相交行对（可合并提示）
     * @param conflicts 异决策相交行对（歧义冲突）
     * @param deadRows  被在先行完全覆盖的死行（FIRST_HIT 永不命中）
     */
    public record DecisionTableFindings(List<OverlapFinding> overlaps,
                                        List<ConflictFinding> conflicts,
                                        List<DeadRuleFinding> deadRows) {}

    /**
     * 对规则集中所有决策表逐表做行内分析。
     *
     * @param rules 待检测规则列表；仅 kind==DECISION_TABLE 且 AST 为 {@link DecisionTableNode} 者参与
     * @return 三类发现汇总，每类按 loc 升序确定性排列；无发现时各为空列表
     */
    public static DecisionTableFindings detect(List<AnalyzableRule> rules) {
        List<OverlapFinding> overlaps = new ArrayList<>();
        List<ConflictFinding> conflicts = new ArrayList<>();
        List<DeadRuleFinding> deadRows = new ArrayList<>();

        for (AnalyzableRule rule : rules) {
            if (!RuleKind.DECISION_TABLE.tag().equals(rule.kind())
                    || !(rule.ast() instanceof DecisionTableNode table)) {
                continue;
            }
            analyzeTable(rule.ruleCode(), table, overlaps, conflicts, deadRows);
        }

        overlaps.sort(Comparator.comparing(OverlapFinding::locA).thenComparing(OverlapFinding::locB));
        conflicts.sort(Comparator.comparing(ConflictFinding::locA).thenComparing(ConflictFinding::locB));
        deadRows.sort(Comparator.comparing(DeadRuleFinding::deadRuleCode)
                .thenComparing(DeadRuleFinding::coveredByRuleCode));
        return new DecisionTableFindings(overlaps, conflicts, deadRows);
    }

    private static void analyzeTable(String tableCode, DecisionTableNode table,
                                     List<OverlapFinding> overlaps,
                                     List<ConflictFinding> conflicts,
                                     List<DeadRuleFinding> deadRows) {
        List<DecisionTableNode.Column> columns = table.columns();
        List<DecisionTableNode.Row> rows = table.rows();

        List<RuleCube> cubes = new ArrayList<>(rows.size());
        for (DecisionTableNode.Row row : rows) {
            cubes.add(rowCube(columns, row));
        }

        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                RuleCube ci = cubes.get(i);
                RuleCube cj = cubes.get(j);
                String locI = loc(tableCode, i);
                String locJ = loc(tableCode, j);
                String decI = rows.get(i).decisionCode();
                String decJ = rows.get(j).decisionCode();

                if (ci.overlaps(cj) == Tri.TRUE) {
                    if (Objects.equals(decI, decJ)) {
                        overlaps.add(new OverlapFinding(locI, locJ,
                                locI + " 与 " + locJ + " 输入相交且决策相同(" + decI + ")，可考虑合并",
                                Severity.INFO));
                    } else {
                        conflicts.add(new ConflictFinding(locI, locJ, decI, decJ,
                                locI + " 与 " + locJ + " 输入相交但产出不同决策(" + decI + " / " + decJ + ")，存在歧义",
                                Severity.WARN));
                    }
                }

                // FIRST_HIT 语义：在先行 i 恒先胜出，若 i 完全覆盖 j 则 j 永不命中（死行）
                if (ci.subsumes(cj) == Tri.TRUE) {
                    deadRows.add(new DeadRuleFinding(locJ, locI,
                            locJ + " 被在先行 " + locI + " 完全覆盖，FIRST_HIT 下永不命中（死行）",
                            Severity.WARN));
                }
            }
        }
    }

    /** 行 loc 标识：0-based 下标 → {@code ruleCode + "#row" + (r+1)}（1-based、人类可读）。 */
    private static String loc(String tableCode, int rowIndex) {
        return tableCode + "#row" + (rowIndex + 1);
    }

    /** 把一行投影为立方体：逐列求空间并按维度键（同 metric 多列取交）合并。 */
    private static RuleCube rowCube(List<DecisionTableNode.Column> columns, DecisionTableNode.Row row) {
        List<Object> cells = row.conditions();
        Map<String, ConditionSpace> dims = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            Object cell = (i < cells.size()) ? cells.get(i) : null;
            if (cell == null) {
                continue; // 通配：该列无约束，留作全集 any（dim 缺失即 any）
            }
            DecisionTableNode.Column col = columns.get(i);
            ConditionSpace space = ConditionSpaceFactory.fromOperator(col.operator(), paramsFor(col.operator(), cell));
            dims.merge(dimKey(col), space, ConditionSpace::meet);
        }
        return new RuleCube(dims);
    }

    /** 维度键：{@code metricCode + "@" + valueRef}；列 valueRef 为 null 时默认 METRIC（与求值期一致）。 */
    private static String dimKey(DecisionTableNode.Column col) {
        ValueRef ref = col.valueRef() == null ? ValueRef.METRIC : col.valueRef();
        return col.metricCode() + "@" + ref.name();
    }

    /**
     * 按算子约定把单元格值塑形为 {@link ConditionSpaceFactory#fromOperator} 期望的参数：
     * IN/NOT_IN → {@code values}；BETWEEN/NOT_BETWEEN → 二元 List [lo,hi] 拆为 {@code min}/{@code max}；
     * 其余单值算子 → {@code threshold}。与 DecisionTableExecutor.buildParams 同一约定。
     */
    private static Map<String, Object> paramsFor(String operator, Object cell) {
        return switch (operator.toUpperCase()) {
            case ConditionTypes.IN, ConditionTypes.NOT_IN -> Map.of(ConditionParams.VALUES, cell);
            case ConditionTypes.BETWEEN, ConditionTypes.NOT_BETWEEN -> {
                // 行单元格约定为二元 List [lo, hi]；用 HashMap 容忍 null 端点（不可塑形交由 fromOperator 降级）
                if (cell instanceof List<?> bounds && bounds.size() == 2
                        && bounds.get(0) != null && bounds.get(1) != null) {
                    Map<String, Object> p = new HashMap<>();
                    p.put(ConditionParams.MIN, bounds.get(0));
                    p.put(ConditionParams.MAX, bounds.get(1));
                    yield p;
                }
                yield Map.of(); // 残缺范围 → 空参 → fromOperator 降级 unknown
            }
            default -> Map.of(ConditionParams.THRESHOLD, cell);
        };
    }
}
