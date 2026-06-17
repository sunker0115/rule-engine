package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.internal.bundle.RuleExportService;
import com.sstlfsj.rule.config.internal.bundle.RuleImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RuleBundleServiceImpl 委托测试（v2 接口）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleBundleServiceImplTest {

    @Mock RuleExportService ruleExportService;
    @Mock RuleImportService ruleImportService;
    @InjectMocks RuleBundleServiceImpl sut;

    private RuleBundle bundle() {
        return new RuleBundle(2, null, "t", "1", List.of(), List.of(), List.of(), List.of());
    }

    private ImportDiffReport emptyReport() {
        return new ImportDiffReport(List.of(), List.of(), List.of(), List.of(), 0, 0, 0);
    }

    @Test
    void export_delegatesToRuleExportService() {
        RuleBundle expected = bundle();
        when(ruleExportService.export(1L, List.of(10L), null)).thenReturn(expected);

        RuleBundle result = sut.export(1L, List.of(10L), null);

        assertThat(result).isSameAs(expected);
        verify(ruleExportService).export(1L, List.of(10L), null);
        verifyNoInteractions(ruleImportService);
    }

    @Test
    void importBundle_dryRunFalse_callsApply() {
        when(ruleImportService.apply(any(), any(), any(), any())).thenReturn(emptyReport());

        ImportDiffReport r = sut.importBundle(1L, bundle(), ImportPolicy.SKIP, false, "actor1");

        assertThat(r).isNotNull();
        verify(ruleImportService).apply(eq(1L), any(RuleBundle.class), eq(ImportPolicy.SKIP), eq("actor1"));
        verify(ruleImportService, never()).dryRun(any(), any(), any(), any());
    }

    @Test
    void importBundle_dryRunTrue_callsDryRun() {
        when(ruleImportService.dryRun(any(), any(), any(), any())).thenReturn(emptyReport());

        ImportDiffReport r = sut.importBundle(1L, bundle(), ImportPolicy.OVERWRITE, true, "actor1");

        assertThat(r).isNotNull();
        verify(ruleImportService).dryRun(eq(1L), any(RuleBundle.class), eq(ImportPolicy.OVERWRITE), eq("actor1"));
        verify(ruleImportService, never()).apply(any(), any(), any(), any());
    }
}
