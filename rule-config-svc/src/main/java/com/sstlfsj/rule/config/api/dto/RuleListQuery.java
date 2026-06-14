package com.sstlfsj.rule.config.api.dto;

/**
 * 规则列表查询条件。新增筛选字段只需改本 record，Controller→Service→Mapper 签名不变。
 *
 * @param tenantId  租户 ID（必填）
 * @param sceneCode 场景编码（选填，不传查全租户）
 * @param status    规则状态（选填）
 * @param from      发布时间起始（选填，ISO 日期）
 * @param to        发布时间截止（选填，ISO 日期）
 * @param page      页码（1-based）
 * @param size      每页条数
 */
public record RuleListQuery(
        String tenantId,
        String sceneCode,
        String status,
        String from,
        String to,
        int page,
        int size
) {
    public RuleListQuery {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
    }
}
