package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.internal.repository.SubjectQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * SQL 类型主体查询实现：执行配置中的 SQL，结果行须含 {@code subjectId} 列。
 *
 * <p>首期仅支持 {@code type=SQL}；EXTERNAL_HTTP / METRIC_RESULT 后续扩展。
 */
@Component
@RequiredArgsConstructor
class SqlSubjectQueryRunner implements SubjectQueryRunner {

    private final SubjectQueryMapper subjectQueryMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<Map<String, Object>> query(String subjectQueryJson) {
        Map<String, Object> cfg = parse(subjectQueryJson);
        String type = (String) cfg.get("type");
        if (!"SQL".equals(type)) {
            throw new IllegalArgumentException("subjectQuery 首期仅支持 type=SQL，收到: " + type);
        }
        Object sqlObj = cfg.get("sql");
        if (!(sqlObj instanceof String sql) || sql.isBlank()) {
            throw new IllegalArgumentException("subjectQuery.sql 不能为空");
        }
        List<Map<String, Object>> rows = subjectQueryMapper.runSql(sql);
        for (Map<String, Object> row : rows) {
            if (!row.containsKey("subjectId")) {
                throw new IllegalArgumentException(
                        "subjectQuery.sql 结果必须含 subjectId 列（用 AS subjectId 别名）");
            }
        }
        return rows;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("subjectQuery 配置不能为空");
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
