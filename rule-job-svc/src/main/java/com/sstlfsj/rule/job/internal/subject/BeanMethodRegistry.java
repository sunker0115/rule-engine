package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
     * 按 ref 反射调用主体查询方法，统一归一为惰性 {@link Stream}。
     * 方法可返回 {@code Stream<JobTarget>}（流式，原样透传）或 {@code Collection<JobTarget>}（如 List，转流）。
     *
     * @param ref {@code <bean>#<method>}
     * @return 方法返回的目标流
     */
    @SuppressWarnings("unchecked")
    public Stream<JobTarget> invoke(String ref) {
        Handle handle = handles.get(ref);
        if (handle == null) {
            throw new IllegalStateException("未注册的 @RuleJob 主体查询方法: " + ref);
        }
        try {
            // 注解 bean 类可能为包私有，放开访问再反射调用
            handle.method().setAccessible(true);
            Object result = handle.method().invoke(handle.bean());
            return switch (result) {
                case Stream<?> s -> (Stream<JobTarget>) s;
                case Collection<?> c -> ((Collection<JobTarget>) c).stream();
                case null -> Stream.empty();
                default -> throw new IllegalStateException(
                        "@RuleJob 方法返回类型不支持（需 List 或 Stream<JobTarget>）: " + ref);
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用 @RuleJob 主体查询方法失败: " + ref, e);
        }
    }
}
