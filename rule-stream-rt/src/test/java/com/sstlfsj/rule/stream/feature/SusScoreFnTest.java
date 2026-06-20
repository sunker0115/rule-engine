package com.sstlfsj.rule.stream.feature;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SusScoreFnTest {
    @Test void zeroAll() { assertThat(SusScoreFn.compute(0, 0, 0)).isEqualTo(0.0); }
    @Test void maxAll() { assertThat(SusScoreFn.compute(100, 1, 100)).isEqualTo(1.0); }
    @Test void typical() { assertThat(SusScoreFn.compute(8, 0.45, 15)).isGreaterThan(0.3).isLessThan(0.7); }
}
