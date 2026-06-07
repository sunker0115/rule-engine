package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;

import java.util.stream.Stream;

/** 主体集合查询 SPI：按 JobDefinition.subjectQuery 配置查出本批次目标。 */
public interface SubjectQueryRunner {

    /**
     * 执行主体查询，返回惰性 {@link Stream}：业务方法返回 {@code List} 时为内存流，
     * 返回 {@code Stream}（如 MyBatis {@code Cursor.stream()}）时为流式拉取，支持大数据量分批不爆内存。
     *
     * <p>调用方须用 try-with-resources 消费以释放底层游标。
     *
     * @param subjectQueryJson 主体查询配置 JSON（含 type 及查询参数）
     * @return 目标流（带 payload / providedMetrics）
     */
    Stream<JobTarget> query(String subjectQueryJson);
}
