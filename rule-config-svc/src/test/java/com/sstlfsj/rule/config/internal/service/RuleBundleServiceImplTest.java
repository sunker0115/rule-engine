package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.internal.bundle.RuleExportService;
import com.sstlfsj.rule.config.internal.bundle.RuleImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RuleBundleServiceImpl 委托测试：验证 export / importBundle 透传到各自内部服务。
 */
@ExtendWith(MockitoExtension.class)
class RuleBundleServiceImplTest {

    @Mock RuleExportService ruleExportService;
    @Mock RuleImportService ruleImportService;
    @InjectMocks RuleBundleServiceImpl sut;

    @Test
    void export_delegatesToRuleExportService() {
        RuleBundle expected = new RuleBundle(1, "t", "1", List.of(), List.of(), List.of(), List.of());
        when(ruleExportService.export("1", List.of(10L), null)).thenReturn(expected);

        RuleBundle result = sut.export("1", List.of(10L), null);

        assertThat(result).isSameAs(expected);
        verify(ruleExportService).export("1", List.of(10L), null);
        verifyNoInteractions(ruleImportService);
    }

    @Test
    void importBundle_delegatesToRuleImportService() {
        RuleBundle bundle = new RuleBundle(1, "t", "1", List.of(), List.of(), List.of(), List.of());
        RuleImportResult expected = new RuleImportResult(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(ruleImportService.importBundle("2", bundle, "actor1")).thenReturn(expected);

        RuleImportResult result = sut.importBundle("2", bundle, "actor1");

        assertThat(result).isSameAs(expected);
        verify(ruleImportService).importBundle("2", bundle, "actor1");
        verifyNoInteractions(ruleExportService);
    }
}
