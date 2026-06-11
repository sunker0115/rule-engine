package com.sstlfsj.rule.eval.internal.event;

/** 内核落库领域事件统一标记;实现者声明自身 durability。 */
public interface DomainEvent {
    Durability durability();
}
