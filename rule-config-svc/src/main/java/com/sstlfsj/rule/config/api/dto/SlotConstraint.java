package com.sstlfsj.rule.config.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** Slot 值的约束条件（可选）。 */
public record SlotConstraint(
        BigDecimal min,
        BigDecimal max,
        List<String> enumValues
) {}
