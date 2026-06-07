package com.sstlfsj.rule.job.internal.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 执行 subjectQuery 配置中的原生只读 SQL，返回主体行。 */
@Mapper
public interface SubjectQueryMapper {

    /**
     * 执行任意只读 SQL 并以行 Map 列表返回。
     *
     * <p>sql 为运营在 {@code job_definition.subject_query} 中配置的可信语句，
     * 经 {@code ${}} 整体拼接（非终端用户输入，不存在外部注入面）。
     *
     * @param sql 完整 SELECT 语句，结果须含 subjectId 列
     * @return 行 Map 列表（列名 → 值）
     */
    @Select("${sql}")
    List<Map<String, Object>> runSql(@Param("sql") String sql);
}
