package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RuleBundleService 接口签名编译验证。
 * 业务逻辑由实现类测试（Task 4）覆盖；此处仅确认接口方法签名可正确引用。
 */
class RuleBundleServiceTest {

    @Test
    void interfaceMethodSignatures_compileAndReflect() throws NoSuchMethodException {
        // export 签名：(Long, List<Long>, Long) → RuleBundle
        var exportMethod = RuleBundleService.class.getMethod("export", Long.class, List.class, Long.class);
        assertThat(exportMethod.getReturnType()).isEqualTo(RuleBundle.class);

        // importBundle 签名：(Long, RuleBundle, String) → RuleImportResult
        var importMethod = RuleBundleService.class.getMethod("importBundle", Long.class, RuleBundle.class, String.class);
        assertThat(importMethod.getReturnType()).isEqualTo(RuleImportResult.class);
    }
}
