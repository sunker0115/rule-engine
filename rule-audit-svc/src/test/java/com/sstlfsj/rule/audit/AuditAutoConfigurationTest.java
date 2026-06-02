package com.sstlfsj.rule.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 AuditAutoConfiguration 能正确注册 AuditService bean。 */
class AuditAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

    @Test
    void auditService_bean已注册() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(AuditService.class));
    }
}
