package com.sstlfsj.rule.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.audit.internal.repository.AuditLogReadMapper;
import com.sstlfsj.rule.audit.internal.repository.EvalSessionReadMapper;
import com.sstlfsj.rule.audit.internal.repository.NodeTraceReadMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证 AuditAutoConfiguration 能正确注册 AuditService bean。 */
class AuditAutoConfigurationTest {

    // 注入三个 Mapper mock，满足 AuditServiceImpl 的构造器依赖
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(EvalSessionReadMapper.class, () -> mock(EvalSessionReadMapper.class))
            .withBean(NodeTraceReadMapper.class, () -> mock(NodeTraceReadMapper.class))
            .withBean(AuditLogReadMapper.class, () -> mock(AuditLogReadMapper.class))
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

    @Test
    void auditService_bean已注册() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(AuditService.class));
    }
}
