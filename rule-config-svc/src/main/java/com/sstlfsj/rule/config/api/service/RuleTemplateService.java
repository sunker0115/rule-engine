package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateDetail;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;
import com.sstlfsj.rule.kernel.api.model.RuleBody;

import java.util.List;
import java.util.Map;

/** 规则模板管理（v2：身份/快照分离，SlotRefResolver SPI 接入，版本化生命周期）。 */
public interface RuleTemplateService {

    /** 创建 DRAFT 模板。建 rule_template(DRAFT) + rule_template_version(v1, DRAFT)。返回模板 ID。 */
    Long create(Long tenantId, String code, String name, String kind,
                String description, RuleBody bodySkeleton,
                List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId);

    /**
     * 更新模板。若存在 DRAFT 版本则原地更新（同 version）；若无 DRAFT（已 PUBLISHED）则新建 v(n+1) DRAFT。
     * DISABLED 状态拒绝更新。
     */
    void update(Long tenantId, String code, String name, String kind,
                String description, RuleBody bodySkeleton,
                List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId);

    /** 发布模板：DRAFT version→PUBLISHED（原地），rule_template.status→PUBLISHED。发布前经 binder.validate 校验。 */
    void publish(Long tenantId, String code, String actorId);

    /** 禁用模板 PUBLISHED→DISABLED。 */
    void disable(Long tenantId, String code, String actorId);

    /** 重新启用模板 DISABLED→PUBLISHED。 */
    void enable(Long tenantId, String code, String actorId);

    /** 按租户列出可见模板（STANDARD 租户可见 SYSTEM 模板 + 自身模板）。 */
    List<RuleTemplate> list(Long tenantId, String status);

    /** 查单个模板身份（可见性过滤）。 */
    RuleTemplate get(Long tenantId, String code);

    /** 查模板身份 + 最新版本快照。 */
    TemplateDetail getVersion(Long tenantId, String code);

    /** 查模板身份 + 指定版本快照。 */
    TemplateDetail getVersion(Long tenantId, String code, Integer version);

    /** 列出模板的所有版本快照（按 version DESC 排序）。 */
    List<RuleTemplateVersion> listVersions(Long tenantId, String code);

    /**
     * 实例化流水线（spec §5.2）：
     * <ol>
     *   <li>DISABLED 模板拒绝</li>
     *   <li>取最新 PUBLISHED 版本</li>
     *   <li>校验 slot 值：必填缺报错；REF slot 经 SlotRefResolver 校验；VALUE slot 类型强转</li>
     *   <li>构建 coercedValues：VALUE 强转值 + REF 原值</li>
     *   <li>TemplateBinder.bind → RuleBody</li>
     *   <li>PublishService.createDraft(5 参版, callerTenantId)</li>
     *   <li>插入 RuleTemplateInstantiation（try-catch best-effort）</li>
     * </ol>
     */
    DraftCreatedResult instantiate(Long tenantId, String templateCode,
                                   String ruleCode, String ruleName,
                                   String sceneCode, List<String> triggerEventTypes,
                                   Map<String, Object> slotValues, String actorId);
}
