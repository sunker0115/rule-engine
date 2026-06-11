package com.sstlfsj.rule.kernel.api.model;

/** RuleEvent 渠道：事件从哪来。由注入入口权威设置，不信外部 JSON。 */
public enum EventSource {
    HTTP, MQ, JOB, SDK, REPLAY
}
