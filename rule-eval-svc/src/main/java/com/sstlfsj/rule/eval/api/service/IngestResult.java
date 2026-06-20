package com.sstlfsj.rule.eval.api.service;

import java.time.Instant;

/** 一次回灌结果:落库接受条数 + 新 watermark(供调用方写回任务游标)。 */
public record IngestResult(int accepted, Instant newWatermark) {}
