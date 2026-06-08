package com.sstlfsj.rule.config.api.service;

/**
 * 租户业务标识（code）→ 内部 surrogate id 解析服务。
 *
 * <p>边界翻译用：外部契约（API/事件）以 {@code tenant.code}（全局唯一业务标识）寻址租户，
 * 内部持久化/索引/外键仍用 {@code tenant.id}（自增 surrogate）。surrogate 不外泄。
 */
public interface TenantQueryService {

    /**
     * 解析租户 code 为内部 id。
     *
     * @param code 租户业务标识（{@code tenant.code}）
     * @return 内部 {@code tenant.id}；code 为空或不存在时返回 null
     */
    Long resolveIdByCode(String code);
}
