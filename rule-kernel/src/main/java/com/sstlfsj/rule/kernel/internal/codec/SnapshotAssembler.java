package com.sstlfsj.rule.kernel.internal.codec;

import tools.jackson.core.JacksonException;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 将 RuleVersionRow（数据库 JOIN 结果）装配为不可变的 RuleVersionSnapshot。
 * 纯 Java，无 Spring 依赖。
 */
public class SnapshotAssembler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotAssembler.class);

    private final AstJsonCodec codec;

    public SnapshotAssembler() {
        this.codec = new AstJsonCodec();
    }

    public SnapshotAssembler(AstJsonCodec codec) {
        this.codec = codec;
    }

    /**
     * 将一行数据库结果装配为 RuleVersionSnapshot。
     *
     * @param row JOIN 查询结果行
     * @return 不可变 RuleVersionSnapshot
     * @throws JacksonException JSON 反序列化失败时抛出
     */
    public RuleVersionSnapshot assemble(RuleVersionRow row) throws JacksonException {
        AstNode conditionAst = codec.deserializeAst(row.conditionAstJson());
        List<RuleVersionSnapshot.PreGateConfig> preGates =
                codec.deserializePreGates(row.preGatesJson());
        List<RuleVersionSnapshot.DecisionBinding> decisionBindings =
                codec.deserializeDecisionBindings(row.decisionBindingsJson());
        List<String> triggerEventTypes = codec.deserializeStringList(
                row.triggerEventTypesJson() == null ? "[]" : row.triggerEventTypesJson());
        List<MetricDependency> metricDependencies = codec.deserializeMetricDependencies(
                row.metricDependenciesJson() == null ? "[]" : row.metricDependenciesJson());
        List<PayloadDependency> payloadDependencies = codec.deserializePayloadDependencies(
                row.payloadDependenciesJson() == null ? "[]" : row.payloadDependenciesJson());

        return new RuleVersionSnapshot(
                row.ruleVersionId(),
                row.sceneCode(),
                String.valueOf(row.tenantId()),
                conditionAst,
                preGates,
                decisionBindings,
                triggerEventTypes,
                row.kind() != null ? row.kind() : RuleKind.AST_BOOLEAN.tag(),
                null,
                0L,
                metricDependencies,
                payloadDependencies
        );
    }

    /**
     * 批量装配，JSON 解析失败的行跳过并记录日志。
     *
     * @param rows 待装配的行列表
     * @return 成功装配的快照列表
     */
    public List<RuleVersionSnapshot> assembleAll(List<RuleVersionRow> rows) {
        return rows.stream()
                .map(row -> {
                    try {
                        return assemble(row);
                    } catch (JacksonException e) {
                        // JSON 格式异常：跳过该行（理论上不应发生，rule_version 由引擎写入）
                        log.warn("跳过解析失败的 ruleVersionId={}: {}", row.ruleVersionId(), e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
