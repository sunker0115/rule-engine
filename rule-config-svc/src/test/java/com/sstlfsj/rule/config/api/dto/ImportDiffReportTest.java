package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportDiffReportTest {

    @Test
    void record_accessors_work() {
        var item = new ImportDiffReport.RuleImportItem("rule.a", "scene1", "目标不存在，将新建");
        var conflict = new ImportDiffReport.RuleImportConflict("rule.b", "scene1", "EXISTING_ACTIVE", "已有 ACTIVE 版本");

        ImportDiffReport report = new ImportDiffReport(
                List.of(item), List.of(), List.of(), List.of(conflict), 1, 0, List.of(), 2);

        assertThat(report.willCreate()).containsExactly(item);
        assertThat(report.conflicts()).containsExactly(conflict);
        assertThat(report.scenesCreated()).isEqualTo(1);
        assertThat(report.decisionsCreated()).isEqualTo(2);
        assertThat(conflict.conflictType()).isEqualTo("EXISTING_ACTIVE");
        assertThat(item.reason()).isEqualTo("目标不存在，将新建");
    }
}
