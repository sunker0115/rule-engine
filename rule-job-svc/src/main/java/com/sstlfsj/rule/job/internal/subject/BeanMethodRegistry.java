package com.sstlfsj.rule.job.internal.subject;

import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code @RuleJob} 主体查询方法注册表：ref（{@code <bean>#<method>}）→ 目标 bean + 方法。
 *
 * <p>由 RuleJobScanner 启动期填充，{@link BeanMethodSubjectQueryRunner} 触发时按 ref 取方法签名分发、反射调用。
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
     * 返回 ref 对应的方法对象，供调用方按签名（无参 / 单 JobPage 参）分发。
     *
     * @param ref {@code <bean>#<method>}
     * @return 方法对象
     */
    public Method method(String ref) {
        return handle(ref).method();
    }

    /**
     * 按 ref 反射调用主体查询方法。
     *
     * @param ref  {@code <bean>#<method>}
     * @param args 调用参数（无参方法传空，分页方法传 JobPage）
     * @return 方法返回值
     */
    public Object invoke(String ref, Object... args) {
        Handle handle = handle(ref);
        try {
            // 注解 bean 类可能为包私有，放开访问再反射调用
            handle.method().setAccessible(true);
            return handle.method().invoke(handle.bean(), args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用 @RuleJob 主体查询方法失败: " + ref, e);
        }
    }

    private Handle handle(String ref) {
        Handle handle = handles.get(ref);
        if (handle == null) {
            throw new IllegalStateException("未注册的 @RuleJob 主体查询方法: " + ref);
        }
        return handle;
    }
}
