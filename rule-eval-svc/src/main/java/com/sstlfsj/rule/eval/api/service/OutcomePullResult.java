package com.sstlfsj.rule.eval.api.service;

import java.time.Instant;
import java.util.List;

/** 一次拉取结果：标签行 + 新 watermark（本批 max labeledAt；空批则原样返回入参 watermark）。 */
public record OutcomePullResult(List<OutcomeService.OutcomeRecord> records, Instant newWatermark) {}
