package com.sstlfsj.rule.job.internal.subject;

import java.util.List;
import java.util.Map;

/** 主体集合查询 SPI：按 JobDefinition.subjectQuery 配置查出本批次主体行。 */
public interface SubjectQueryRunner {

    /**
     * 执行主体查询，返回主体行列表。
     *
     * @param subjectQueryJson 主体查询配置 JSON（含 type 及查询参数）
     * @return 主体行列表，每行必须含 {@code subjectId} 键；其余键供 payloadTemplate 占位符填充
     */
    List<Map<String, Object>> query(String subjectQueryJson);
}
