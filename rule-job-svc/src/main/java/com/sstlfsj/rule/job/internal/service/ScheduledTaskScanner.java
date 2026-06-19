package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.api.annotation.TriggerTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.subject.BeanMethodRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * 扫 {@code @TriggerTask} 注解方法，启动期填充主体查询方法注册表并 upsert 到 {@code scheduled_task}（TRIGGER 型）。
 *
 * <p>在所有单例就绪后（{@link SmartInitializingSingleton}）、{@link ScheduledTaskStartupRegistrar}
 * （ApplicationRunner）之前执行：注解任务落库为 typed {@link TriggerConfig} 后，由 StartupRegistrar
 * 统一注册到调度器。按 (tenant, code) 幂等 upsert。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ScheduledTaskScanner implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final ScheduledTaskMapper taskMapper;
    private final BeanMethodRegistry registry;

    @Override
    public void afterSingletonsInstantiated() {
        int count = 0;
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = applicationContext.getType(beanName);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null) {
                continue;
            }
            for (Method method : ClassUtils.getUserClass(type).getMethods()) {
                TriggerTask ann = AnnotationUtils.findAnnotation(method, TriggerTask.class);
                if (ann != null) {
                    registerOne(applicationContext.getBean(beanName), method, ann);
                    count++;
                }
            }
        }
        if (count > 0) {
            log.info("[scheduled-task] @TriggerTask 扫描完成,注册 {} 个 TRIGGER 任务", count);
        }
    }

    private void registerOne(Object bean, Method method, TriggerTask ann) {
        String ref = method.getDeclaringClass().getName() + "#" + method.getName();
        registry.register(ref, bean, method);
        Long tenantId = Long.valueOf(ann.tenant());
        String name = ann.name().isBlank() ? ann.code() : ann.name();
        TriggerConfig config = new TriggerConfig(ann.scene(), ann.eventType(), new BeanMethodQuery(ref));
        ScheduledTask existing = taskMapper.findByTenantCode(tenantId, ann.code());
        if (existing == null) {
            ScheduledTask t = new ScheduledTask();
            t.setTenantId(tenantId);
            t.setCode(ann.code());
            t.setName(name);
            t.setTaskType(TaskType.TRIGGER);
            t.setCron(ann.cron());
            t.setConfig(config);
            t.setStatus(TaskStatus.ACTIVE);
            t.setCreatedBy("@TriggerTask");
            taskMapper.insert(t);
        } else {
            // 保留既有 status（启用 / 禁用由管理接口决定），只刷新展示名 / cron / config
            existing.setName(name);
            existing.setCron(ann.cron());
            existing.setConfig(config);
            existing.setUpdatedBy("@TriggerTask");
            taskMapper.updateById(existing);
        }
        log.info("[scheduled-task] TRIGGER: code={} cron={} scene={} ref={}", ann.code(), ann.cron(), ann.scene(), ref);
    }
}
