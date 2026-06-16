package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScoreBandTest {

    @Test
    void accessors_retainConstructorValues() {
        ScoreBand band = new ScoreBand(0, 60, "REJECT", "HIGH_RISK");
        assertEquals(0, band.minScore(), 1e-9);
        assertEquals(60, band.maxScore(), 1e-9);
        assertEquals("REJECT", band.decisionCode());
        assertEquals("HIGH_RISK", band.category());
    }

    @Test
    void category_isNullable() {
        ScoreBand band = new ScoreBand(60, 80, "REVIEW", null);
        assertNull(band.category());
    }

    @Test
    void recordEquality_byValue() {
        ScoreBand a = new ScoreBand(0, 60, "REJECT", "HIGH");
        ScoreBand b = new ScoreBand(0, 60, "REJECT", "HIGH");
        assertEquals(a, b);
    }
}
