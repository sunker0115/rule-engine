package com.sstlfsj.rule.eval.internal.event;

/** 领域事件投递可靠性等级:进程内为路由元数据,MQ 决定 topic/ack。 */
public enum Durability { BEST_EFFORT, AT_LEAST_ONCE }
