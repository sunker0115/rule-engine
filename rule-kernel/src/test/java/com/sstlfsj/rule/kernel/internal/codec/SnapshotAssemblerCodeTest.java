package com.sstlfsj.rule.kernel.internal.codec;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotAssemblerCodeTest {
    @Test
    void assembleCarriesCodeAndVersion() throws Exception {
        RuleVersionRow row = new RuleVersionRow(
                100L, "scene", 1L, "{\"type\":\"AndNode\",\"children\":[]}",
                "[]", "[]", "[\"ev\"]", "AST_BOOLEAN", "HIGHEST_PRIORITY",
                "[]", "[]", "large-trade", 3L);
        RuleVersionSnapshot s = new SnapshotAssembler().assemble(row);
        assertThat(s.code()).isEqualTo("large-trade");
        assertThat(s.version()).isEqualTo(3L);
    }
}
