package com.sstlfsj.rule.kernel.api.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 分析报告契约的构造/访问器测试,覆盖每类发现的字段与 Severity 取值。 */
class RuleSetAnalysisReportTest {

    @Test
    void constructsReportWithOneOfEachFindingType() {
        var incoherence = new IncoherenceFinding("R1", "age > 5 AND age < 3", Severity.ERROR);
        var deadRule = new DeadRuleFinding("R2", "R1", "completely covered by R1", Severity.WARN);
        var conflict = new ConflictFinding("R3", "T1#row2", "APPROVE", "REJECT", "opposite decisions", Severity.ERROR);
        var overlap = new OverlapFinding("R4", "R5", "ranges intersect", Severity.INFO);
        var coverageGap = new CoverageGapFinding("D_REVIEW", "no rule path yields it", Severity.WARN);
        var unanalyzable = new UnanalyzableRule("R6", "contains OR/regex");
        var redundancy = new RedundancyFinding("R7", "amount LTE 10", "amount EQ 10",
                "amount LTE 10 被同组 amount EQ 10 蕴含，可删除", Severity.INFO);

        var report = new RuleSetAnalysisReport(
                "scene-1",
                List.of(incoherence),
                List.of(deadRule),
                List.of(conflict),
                List.of(overlap),
                List.of(coverageGap),
                List.of(unanalyzable),
                List.of(redundancy)
        );

        assertThat(report.sceneCode()).isEqualTo("scene-1");

        // 自身矛盾
        assertThat(report.incoherences()).singleElement().satisfies(f -> {
            assertThat(f.ruleCode()).isEqualTo("R1");
            assertThat(f.reason()).isEqualTo("age > 5 AND age < 3");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
        });

        // 死规则
        assertThat(report.deadRules()).singleElement().satisfies(f -> {
            assertThat(f.deadRuleCode()).isEqualTo("R2");
            assertThat(f.coveredByRuleCode()).isEqualTo("R1");
            assertThat(f.severity()).isEqualTo(Severity.WARN);
        });

        // 冲突:loc 用 ruleCode / 决策表行 "<tableCode>#row{n}"
        assertThat(report.conflicts()).singleElement().satisfies(f -> {
            assertThat(f.locA()).isEqualTo("R3");
            assertThat(f.locB()).isEqualTo("T1#row2");
            assertThat(f.decisionA()).isEqualTo("APPROVE");
            assertThat(f.decisionB()).isEqualTo("REJECT");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
        });

        // 重叠
        assertThat(report.overlaps()).singleElement().satisfies(f -> {
            assertThat(f.locA()).isEqualTo("R4");
            assertThat(f.locB()).isEqualTo("R5");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });

        // 覆盖缺口
        assertThat(report.coverageGaps()).singleElement().satisfies(f -> {
            assertThat(f.decisionCode()).isEqualTo("D_REVIEW");
            assertThat(f.severity()).isEqualTo(Severity.WARN);
        });

        // 不可分析:跳过非"无问题"
        assertThat(report.unanalyzableRules()).singleElement().satisfies(f -> {
            assertThat(f.ruleCode()).isEqualTo("R6");
            assertThat(f.reason()).isEqualTo("contains OR/regex");
        });

        // 单规则内冗余条件
        assertThat(report.redundancies()).singleElement().satisfies(f -> {
            assertThat(f.ruleCode()).isEqualTo("R7");
            assertThat(f.redundantCondition()).isEqualTo("amount LTE 10");
            assertThat(f.impliedByCondition()).isEqualTo("amount EQ 10");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });
    }
}
