package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.dto.RuleListQuery;
import com.sstlfsj.rule.config.api.dto.TenantItemVO;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 规则定义生命周期管理：发布、禁用、查询。 */
public interface ConfigService {

    /**
     * 发布规则定义的最新草稿版本，使其进入激活状态。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待发布的规则定义 ID
     * @param actorId          触发发布的操作人 ID
     * @return 新激活的规则版本快照
     */
    RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * 禁用规则定义：仅 PUBLISHED → DISABLED，其它态拒绝。倒排索引热摘除。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待禁用的规则定义 ID
     * @param actorId          触发禁用的操作人 ID
     */
    void disable(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * 重新启用规则定义：仅 DISABLED → PUBLISHED，其它态拒绝。指向原 current_version，不增版本。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待启用的规则定义 ID
     * @param actorId          触发启用的操作人 ID
     */
    void enable(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * 查询规则列表，支持按 sceneCode / status / 时间范围 过滤，结果分页返回。
     *
     * @param q 封装所有查询条件（tenantId / sceneCode / status / from / to / page / size），
     *          新增筛选字段只需改 RuleListQuery record
     * @return 分页规则列表
     */
    Page<RuleDefinition> listRules(RuleListQuery q);

    /**
     * 查询规则详情：定义基本信息 + 当前 ACTIVE 版本的 conditionAst / decisionBindings。
     *
     * @param tenantId 租户 ID
     * @param ruleId   规则定义 ID
     * @return 规则详情
     */
    RuleDetailVO getRuleDetail(String tenantId, Long ruleId);

    /**
     * 创建规则草稿：新建 rule_definition（DRAFT）+ rule_version（DRAFT）。
     *
     * @param tenantId              租户 ID
     * @param sceneCode             场景编码
     * @param code                  规则编码
     * @param name                  规则名称
     * @param conditionAst          条件 AST，null 视为空 AND
     * @param decisionBindings      决策绑定列表（草稿期 priority 占位，发布时回填），null 视为空
     * @param preGates              前置门列表，null 视为空
     * @param triggerEventTypes     触发事件类型列表，null 视为空
     * @param kind                  规则类型（AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE / EXPRESSION_SCRIPT），null 时默认 AST_BOOLEAN
     * @param script                EXPRESSION_SCRIPT 脚本载体，其它 kind 传 null
     * @param actorId               操作人 ID
     * @return 新建草稿的 ID 信息
     */
    DraftCreatedResult createDraft(String tenantId, String sceneCode,
            String code, String name,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            String kind, ScriptSource script, String actorId);

    /**
     * 原地编辑规则最新 DRAFT 版本（不增版本）：重跑 resolveAndValidate 冻结新内容到同一草稿行。
     *
     * @param tenantId          租户 ID
     * @param ruleId            规则定义 ID
     * @param name              新规则名称，null/空白时不改
     * @param kind              规则类型字符串（AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE），null 时默认 AST_BOOLEAN
     * @param conditionAst      新条件 AST，null 视为空 AND
     * @param decisionBindings  新决策绑定列表（草稿期 priority 占位，发布时回填），null 视为空
     * @param preGates          新前置门列表，null 视为空
     * @param triggerEventTypes 新触发事件类型列表，null 视为空
     * @param script            EXPRESSION_SCRIPT 脚本载体，其它 kind 传 null
     * @param actorId           操作人 ID
     * @return 被更新草稿的 ID 信息（version 不变）
     */
    DraftCreatedResult editDraft(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            ScriptSource script, String actorId);

    /**
     * 给已发布规则出新版本草稿（v_max+1, DRAFT）：要求当前无未发布 DRAFT。
     * fromVersionId 非空时为回退（克隆该版本内容并按当前世界重解析）；激活仍走显式 publish。
     *
     * @param tenantId          租户 ID
     * @param ruleId            规则定义 ID
     * @param name              新规则名称，null/空白时不改
     * @param kind              规则类型字符串（AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE），null 时下游兜底
     * @param conditionAst      新条件 AST（fromVersionId 非空时忽略，改用克隆值）
     * @param decisionBindings  新决策绑定列表（草稿期 priority 占位，发布时回填；fromVersionId 非空时忽略），null 视为空
     * @param preGates          新前置门列表（fromVersionId 非空时忽略），null 视为空
     * @param triggerEventTypes 新触发事件类型列表（fromVersionId 非空时忽略），null 视为空
     * @param fromVersionId     回退源版本 ID，非空时克隆其内容；null 时按入参建新草稿
     * @param script            EXPRESSION_SCRIPT 脚本载体（fromVersionId 非空时忽略），其它 kind 传 null
     * @param actorId           操作人 ID
     * @return 新建草稿的 ID 信息（version = v_max+1）
     */
    DraftCreatedResult newVersion(String tenantId, Long ruleId, String name, String kind,
            AstNode conditionAst, List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates, List<String> triggerEventTypes,
            Long fromVersionId, ScriptSource script, String actorId);

    /**
     * 删整条未发布规则（级联删 rule_definition + 全部 rule_version）：仅当从未发布过。
     *
     * @param tenantId 租户 ID
     * @param ruleId   规则定义 ID
     * @param actorId  操作人 ID
     */
    void deleteRule(String tenantId, Long ruleId, String actorId);

    /**
     * 删单个待发布草稿版本：仅当该版本是 DRAFT（线上 ACTIVE/SUPERSEDED 不动）。
     *
     * @param tenantId  租户 ID
     * @param ruleId    规则定义 ID
     * @param versionId 待删版本 ID（须归属该规则）
     * @param actorId   操作人 ID
     */
    void deleteDraftVersion(String tenantId, Long ruleId, Long versionId, String actorId);

    /**
     * 查询所有启用状态的租户，供前端下拉选择器使用。
     *
     * @return 租户列表
     */
    List<TenantItemVO> listTenants(String keyword, String status);

    /** 启/禁租户。 */
    void toggleTenantStatus(Long tenantId, boolean enable);

    /**
     * 批量查 sceneId → sceneCode 映射，供 controller 层回填 RuleListItemVO。
     */
    Map<Long, String> getSceneCodeMap(Set<Long> sceneIds);
}
