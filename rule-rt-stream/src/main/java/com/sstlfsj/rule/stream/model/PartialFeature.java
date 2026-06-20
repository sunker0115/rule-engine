package com.sstlfsj.rule.stream.model;

/** 单字段部分特征——三条窗口流 union 的统一元素。eventTime 供 merger 注册 timer / 算 updated_at。 */
public class PartialFeature {
    public String customerId;
    public FeatureField field;
    public double value;
    public long eventTime;   // 该特征对应的 event-time（毫秒）

    public PartialFeature() {}

    public PartialFeature(String customerId, FeatureField field, double value, long eventTime) {
        this.customerId = customerId;
        this.field = field;
        this.value = value;
        this.eventTime = eventTime;
    }
}
