package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;

import java.util.List;

/** 主体集合查询 SPI：按 JobDefinition.subjectQuery 配置查出本批次目标。 */
public interface SubjectQueryRunner {

    /**
     * 执行主体查询。
     *
     * @param subjectQueryJson 主体查询配置 JSON（含 type 及查询参数）
     * @return 目标列表（带 payload / providedMetrics）
     */
    List<JobTarget> query(String subjectQueryJson);
}
