package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.JobTarget;
import com.sstlfsj.rule.job.api.SubjectQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

/**
 * 主体查询实现：解析 subjectQuery 配置为 typed {@link SubjectQuery}，按子类型反射调用
 * {@code @RuleJob} 注解的业务查询方法取目标（{@link JobTarget} 列表）。
 *
 * <p>首期仅 BEAN_METHOD；新增子类型只需扩 {@code SubjectQuery} permits + 下方 switch 分支。
 */
@Component
@RequiredArgsConstructor
class BeanMethodSubjectQueryRunner implements SubjectQueryRunner {

    private final BeanMethodRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public Stream<JobTarget> query(String subjectQueryJson) {
        if (subjectQueryJson == null || subjectQueryJson.isBlank()) {
            throw new IllegalArgumentException("subjectQuery 配置不能为空");
        }
        SubjectQuery query = objectMapper.readValue(subjectQueryJson, SubjectQuery.class);
        return switch (query) {
            case BeanMethodQuery b -> registry.invoke(b.ref());
        };
    }
}
