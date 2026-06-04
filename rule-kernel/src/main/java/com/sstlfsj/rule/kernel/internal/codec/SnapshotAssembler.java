package com.sstlfsj.rule.kernel.internal.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/**
 * 将 RuleVersionRow（数据库 JOIN 结果）装配为不可变的 RuleVersionSnapshot。
 * 纯 Java，无 Spring 依赖。
 */
public class SnapshotAssembler {

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
     * @throws JsonProcessingException JSON 反序列化失败时抛出
     */
    public RuleVersionSnapshot assemble(RuleVersionRow row) throws JsonProcessingException {
        AstNode conditionAst = codec.deserializeAst(row.conditionAstJson());
        List<RuleVersionSnapshot.PreGateConfig> preGates =
                codec.deserializePreGates(row.preGatesJson());
        List<RuleVersionSnapshot.DecisionBinding> decisionBindings =
                codec.deserializeDecisionBindings(row.decisionBindingsJson());
        List<String> triggerEventTypes = codec.deserializeStringList(
                row.triggerEventTypesJson() == null ? "[]" : row.triggerEventTypesJson());

        return new RuleVersionSnapshot(
                row.ruleVersionId(),
                row.sceneCode(),
                String.valueOf(row.tenantId()),
                conditionAst,
                preGates,
                decisionBindings,
                triggerEventTypes,
                row.kind() != null ? row.kind() : "AST_BOOLEAN"
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
                    } catch (JsonProcessingException e) {
                        // JSON 格式异常：跳过该行（理论上不应发生，rule_version 由引擎写入）
                        System.err.println("[SnapshotAssembler] 跳过解析失败的 ruleVersionId=" +
                                row.ruleVersionId() + ": " + e.getMessage());
                        return null;
                    }
                })
                .filter(s -> s != null)
                .toList();
    }
}
