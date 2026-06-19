package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.annotation.TriggerTask;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobStatus;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.subject.BeanMethodRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 扫描 {@code @TriggerTask} 注解方法，启动期填充主体查询方法注册表并 upsert 到 {@code job_definition}。
 *
 * <p>在所有单例就绪后（{@link SmartInitializingSingleton}）、{@link JobStartupRegistrar}
 * （ApplicationRunner）之前执行：注解 Job 落库为 BEAN_METHOD 类型后，由 JobStartupRegistrar
 * 统一注册到调度器，与 DB（SQL 类型）Job 同路调度。按 (tenant, scene, code) 幂等 upsert。
 */
@Component
@RequiredArgsConstructor
class RuleJobScanner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RuleJobScanner.class);

    private final ApplicationContext applicationContext;
    private final JobDefinitionMapper jobMapper;
    private final BeanMethodRegistry registry;
    private final ObjectMapper objectMapper;

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
            Class<?> userClass = ClassUtils.getUserClass(type);
            for (Method method : userClass.getMethods()) {
                TriggerTask ann = AnnotationUtils.findAnnotation(method, TriggerTask.class);
                if (ann != null) {
                    registerRuleJob(applicationContext.getBean(beanName), method, ann);
                    count++;
                }
            }
        }
        if (count > 0) {
            log.info("[rule-job] @TriggerTask 注解扫描完成，注册 {} 个注解 Job", count);
        }
    }

    private void registerRuleJob(Object bean, Method method, TriggerTask ann) {
        String ref = method.getDeclaringClass().getName() + "#" + method.getName();
        registry.register(ref, bean, method);
        upsert(ann, ref);
        log.info("[rule-job] 注解 Job: code={} cron={} scene={} ref={}",
                ann.code(), ann.cron(), ann.scene(), ref);
    }

    private void upsert(TriggerTask ann, String ref) {
        String subjectQuery = objectMapper.writeValueAsString(
                Map.of("type", "BEAN_METHOD", "ref", ref));
        Long tenantId = Long.valueOf(ann.tenant());
        String name = ann.name().isBlank() ? ann.code() : ann.name();

        JobDefinition existing = jobMapper.findByTenantSceneCode(tenantId, ann.scene(), ann.code());
        if (existing == null) {
            JobDefinition def = new JobDefinition();
            def.setTenantId(tenantId);
            def.setSceneCode(ann.scene());
            def.setCode(ann.code());
            def.setName(name);
            def.setCronExpression(ann.cron());
            def.setSubjectQuery(subjectQuery);
            def.setEventType(ann.eventType());
            def.setStatus(JobStatus.ACTIVE);
            def.setCreatedBy("@TriggerTask");
            jobMapper.insert(def);
        } else {
            existing.setName(name);
            existing.setCronExpression(ann.cron());
            existing.setSubjectQuery(subjectQuery);
            existing.setEventType(ann.eventType());
            existing.setStatus(JobStatus.ACTIVE);
            existing.setUpdatedBy("@TriggerTask");
            existing.setUpdatedAt(LocalDateTime.now());
            jobMapper.updateById(existing);
        }
    }
}
