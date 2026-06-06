package com.sstlfsj.rule.sdk;

/** 规则快照订阅模式。 */
public enum FetchMode {
    /** 仅拉取 scenes 配置列表中的 scene。 */
    DECLARED,
    /** 拉取租户下所有 ACTIVE 规则。 */
    ALL,
    /** 首次 evaluate 时按 sceneCode 按需拉取，后台定时刷新。 */
    LAZY
}
