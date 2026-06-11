package com.sstlfsj.rule.sdk;

/** 决策命中事件的消费端;DecisionDispatcher 对每个命中决策回调一次。 */
@FunctionalInterface
public interface DecisionSink {
    /** 消费一个决策命中事件。实现自行处理异常隔离;DecisionDispatcher 也会在 sink 外层兜底吞异常。 */
    void accept(DecisionFiredEvent event);
}
