package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.dto.ConnectorListQuery;

import java.util.List;

/** 连接器写服务（CRUD，无 publish 流程，校验在写时做）。 */
public interface ConnectorWriteService {

    /**
     * 创建连接器（置 ACTIVE，写时校验，发审计 + 失效事件）。
     *
     * @return 新建行 id
     */
    Long create(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId);

    /**
     * 原地更新连接器描述符（不升版，发审计 + 失效事件）。
     *
     * @return 受影响行数
     */
    int update(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId);

    /**
     * 停用连接器（status ACTIVE→DISABLED，发审计 + 失效事件）。
     * 停用后 resolver 解析不到（只查 ACTIVE），引用它的 metric 取数 NOT_FOUND（与 disable metric 对称）。
     */
    void disable(Long tenantId, String connectorCode, String actorId);

    /** 分页查询连接器，返回出契约的 View（不泄漏内部实体）。 */
    Page<ConnectorView> listPage(ConnectorListQuery q);

    /** 列出 ACTIVE 连接器；tenantId 为 null 时返回全部租户（供内部校验用）。 */
    List<ConnectorView> listActive(Long tenantId);

    /**
     * 查租户内单个连接器完整信息（任意状态，含 typed descriptor），供前端编辑器直接加载。
     *
     * @return 连接器详情视图；不存在抛 {@link IllegalArgumentException}
     */
    ConnectorDetailView getByCode(Long tenantId, String connectorCode);

    /**
     * 写命令（typed）。
     *
     * @param name       展示名
     * @param descriptor 连接器描述符
     */
    record ConnectorWriteCommand(String name, ConnectorDescriptor descriptor) {}

    /**
     * 列表视图（出契约边界 status 以 String）。
     *
     * @param connectorCode 编码
     * @param name          名称
     * @param status        状态名
     */
    record ConnectorView(Long tenantId, String connectorCode, String name, String status,
                         String createdAt, String updatedAt) {}

    /**
     * 详情视图（含 typed descriptor，出契约边界 status 以 String）。
     *
     * @param connectorCode 编码
     * @param name          名称
     * @param descriptor    连接器描述符（typed，不转 String）
     * @param status        状态名
     */
    record ConnectorDetailView(String connectorCode, String name,
                               ConnectorDescriptor descriptor, String status,
                               String createdAt, String updatedAt) {}
}
