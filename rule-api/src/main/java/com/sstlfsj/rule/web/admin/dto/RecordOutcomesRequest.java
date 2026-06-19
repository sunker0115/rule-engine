package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 标签回灌请求体（B32）。 */
public record RecordOutcomesRequest(
        @NotNull Long tenantId,
        @NotEmpty @Valid List<OutcomeItem> outcomes) {

    /** 单条回灌项。 */
    public record OutcomeItem(
            @NotBlank String eventId,
            @NotBlank String outcomeLabel,
            BigDecimal outcomeValue,
            @NotNull Instant labeledAt,
            String source,
            String note) {}
}
