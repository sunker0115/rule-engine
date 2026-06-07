package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 主体查询实现：解析 subjectQuery 配置（{@code type=BEAN_METHOD}），反射调用 {@code @RuleJob}
 * 注解的业务查询方法取目标（{@link JobTarget} 列表）。
 *
 * <p>首期仅支持 BEAN_METHOD（主体由开发者业务方法返回，如「查 10 分钟前登录的用户」）。
 * 将来若新增 EXTERNAL_HTTP / METRIC_RESULT 等多种 type，再抽出按 type 分发层。
 */
@Component
@RequiredArgsConstructor
class BeanMethodSubjectQueryRunner implements SubjectQueryRunner {

    private final BeanMethodRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public List<JobTarget> query(String subjectQueryJson) {
        Map<String, Object> config = parse(subjectQueryJson);
        Object type = config.get("type");
        if (!"BEAN_METHOD".equals(type)) {
            throw new IllegalArgumentException("不支持的 subjectQuery type: " + type + "（当前仅 BEAN_METHOD）");
        }
        Object ref = config.get("ref");
        if (!(ref instanceof String r) || r.isBlank()) {
            throw new IllegalArgumentException("subjectQuery.ref 不能为空");
        }
        return registry.invoke(r);
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("subjectQuery 配置不能为空");
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
