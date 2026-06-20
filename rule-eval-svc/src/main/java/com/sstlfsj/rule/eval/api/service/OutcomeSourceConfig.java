package com.sstlfsj.rule.eval.api.service;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** 回灌标签来源配置（多态 kind 判别）。扩展新源在 permits + @JsonSubTypes 追加。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({ @JsonSubTypes.Type(value = SqlOutcomeSourceConfig.class, name = "SQL") })
public sealed interface OutcomeSourceConfig permits SqlOutcomeSourceConfig {}
