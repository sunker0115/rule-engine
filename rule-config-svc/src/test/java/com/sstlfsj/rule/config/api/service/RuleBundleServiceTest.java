package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleBundleService v2 接口签名编译验证。 */
class RuleBundleServiceTest {

    @Test
    void interfaceMethodSignatures_compileAndReflect() throws NoSuchMethodException {
        // export 签名：(Long, List<Long>, Long) → RuleBundle
        var exportMethod = RuleBundleService.class.getMethod("export", Long.class, List.class, Long.class);
        assertThat(exportMethod.getReturnType()).isEqualTo(RuleBundle.class);

        // importBundle v2 签名：(Long, RuleBundle, ImportPolicy, boolean, String) → ImportDiffReport
        var importMethod = RuleBundleService.class.getMethod("importBundle",
                Long.class, RuleBundle.class, ImportPolicy.class, boolean.class, String.class);
        assertThat(importMethod.getReturnType()).isEqualTo(ImportDiffReport.class);
    }
}
