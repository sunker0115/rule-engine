package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

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
     * 禁用规则定义及其当前激活版本。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待禁用的规则定义 ID
     * @param actorId          触发禁用的操作人 ID
     */
    void disable(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * 查询规则列表，支持按 sceneCode / status 过滤，结果分页返回。
     *
     * @param tenantId  租户 ID
     * @param sceneCode Scene 编码（null 或空字符串时不过滤）
     * @param status    规则状态过滤（null 时不过滤；DRAFT / PUBLISHED / DISABLED）
     * @param page      页码（从 1 开始）
     * @param size      每页条数
     * @return 分页规则列表
     */
    Page<RuleListItemVO> listRules(String tenantId, String sceneCode, String status, int page, int size);
}
