package com.sstlfsj.rule.kernel.api.model.flow;

/**
 * 有向边：from 节点 → to 节点。caseKey 仅 Switch 出边非空（标识走哪个分支）；
 * 其余边及 Switch 的 default 出边 caseKey 为 null。
 *
 * @param from    源节点 id
 * @param to      目标节点 id
 * @param caseKey Switch 分支键（nullable；null 表示无条件边 / default）
 */
public record FlowEdge(String from, String to, String caseKey) {}
