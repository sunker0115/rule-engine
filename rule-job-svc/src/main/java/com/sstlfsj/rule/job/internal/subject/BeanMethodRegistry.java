package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code @RuleJob} 主体查询方法注册表：ref（{@code <bean>#<method>}）→ 目标 bean + 方法。
 *
 * <p>由 RuleJobScanner 启动期填充，{@link BeanMethodSubjectQueryRunner} 触发时按 ref 反射调用。
 */
@Component
public class BeanMethodRegistry {

    private record Handle(Object bean, Method method) {}

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    /** 注册一个主体查询方法。 */
    public void register(String ref, Object bean, Method method) {
        handles.put(ref, new Handle(bean, method));
    }

    /**
     * 按 ref 反射调用主体查询方法。
     *
     * @param ref {@code <bean>#<method>}
     * @return 方法返回的目标列表
     */
    @SuppressWarnings("unchecked")
    public List<JobTarget> invoke(String ref) {
        Handle handle = handles.get(ref);
        if (handle == null) {
            throw new IllegalStateException("未注册的 @RuleJob 主体查询方法: " + ref);
        }
        try {
            // 注解 bean 类可能为包私有，放开访问再反射调用
            handle.method().setAccessible(true);
            Object result = handle.method().invoke(handle.bean());
            return (List<JobTarget>) result;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用 @RuleJob 主体查询方法失败: " + ref, e);
        }
    }
}
