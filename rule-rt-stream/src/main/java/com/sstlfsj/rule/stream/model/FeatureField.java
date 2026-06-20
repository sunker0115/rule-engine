package com.sstlfsj.rule.stream.model;

/** 部分特征字段判别（封闭取值，union 后 merger 按它覆盖对应字段）。 */
public enum FeatureField {
    RTM_1S, RTM_10S, RTM_30S, RTM_1M, RTM_2M, RTM_5M, RTD_AMOUNT, FAST_TRADE_RATIO
}
