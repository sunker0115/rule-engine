package com.sstlfsj.rule.config.api.event;

/**
 * 连接器变更事件（A 类跨模块集成）：config 写后发布，eval 侧 @ApplicationModuleListener 消费以失效连接器缓存。
 * 用 ApplicationEventPublisher 发，提交后异步消费。
 *
 * @param tenantId      租户 id
 * @param connectorCode 连接器编码
 */
public record ConnectorChangedEvent(String tenantId, String connectorCode) {}
