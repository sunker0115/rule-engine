package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;

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

    /** 列出租户内全部 ACTIVE 连接器。 */
    List<ConnectorView> listActive(Long tenantId);

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
    record ConnectorView(String connectorCode, String name, String status) {}
}
