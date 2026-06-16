package com.sstlfsj.rule.config.api.dto;

/**
 * 连接器列表查询条件。新增筛选字段只改本 record，Controller→Service→Mapper 签名不变。
 *
 * @param tenantId  租户 ID（可选，不传查全部）
 * @param keyword   关键词（可选，匹配编码或名称）
 * @param status    状态（可选，ACTIVE / DISABLED）
 * @param page      页码（1-based）
 * @param size      每页条数
 */
public record ConnectorListQuery(
        String tenantId,
        String keyword,
        String status,
        int page,
        int size
) {
    public ConnectorListQuery {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
    }
}
