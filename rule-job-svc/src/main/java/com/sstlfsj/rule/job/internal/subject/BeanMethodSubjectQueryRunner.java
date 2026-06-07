package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.JobPage;
import com.sstlfsj.rule.job.api.JobTarget;
import com.sstlfsj.rule.job.api.SubjectQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

/**
 * 主体查询实现：解析 subjectQuery 配置为 typed {@link SubjectQuery}，按 {@code @RuleJob} 方法签名分发——
 * 无参方法一次性取 {@code List<JobTarget>}；单 {@link JobPage} 参方法分页拉取（仿 ElasticJob DataflowJob，
 * page 0、1、2… 拉到空批为止）。逐个推给 sink。
 *
 * <p>首期仅 BEAN_METHOD；新增子类型只需扩 {@code SubjectQuery} permits + 下方 switch 分支。
 */
@Component
@RequiredArgsConstructor
class BeanMethodSubjectQueryRunner implements SubjectQueryRunner {

    /** 分页方法的框架建议每页条数。 */
    private static final int PAGE_SIZE = 500;

    private final BeanMethodRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void forEachTarget(String subjectQueryJson, Consumer<JobTarget> sink) {
        if (subjectQueryJson == null || subjectQueryJson.isBlank()) {
            throw new IllegalArgumentException("subjectQuery 配置不能为空");
        }
        SubjectQuery query = objectMapper.readValue(subjectQueryJson, SubjectQuery.class);
        String ref = switch (query) {
            case BeanMethodQuery b -> b.ref();
        };

        Method method = registry.method(ref);
        int paramCount = method.getParameterCount();
        if (paramCount == 0) {
            asTargets(registry.invoke(ref), ref).forEach(sink);
        } else if (paramCount == 1 && method.getParameterTypes()[0] == JobPage.class) {
            // ElasticJob DataflowJob 式分页：page 0、1、2… 直到返回空批
            for (int pageNumber = 0; ; pageNumber++) {
                List<JobTarget> batch = asTargets(
                        registry.invoke(ref, new JobPage(pageNumber, PAGE_SIZE)), ref);
                if (batch.isEmpty()) {
                    break;
                }
                batch.forEach(sink);
            }
        } else {
            throw new IllegalStateException(
                    "@RuleJob 方法签名不支持（须无参，或单 JobPage 参）: " + ref);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<JobTarget> asTargets(Object result, String ref) {
        if (result == null) {
            return List.of();
        }
        if (result instanceof List<?> list) {
            return (List<JobTarget>) list;
        }
        throw new IllegalStateException("@RuleJob 方法须返回 List<JobTarget>: " + ref);
    }
}
