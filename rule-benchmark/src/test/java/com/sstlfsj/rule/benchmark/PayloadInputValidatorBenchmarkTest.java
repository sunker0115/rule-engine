package com.sstlfsj.rule.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/** 校准测试:三档 setup 都构造合法输入,validate() 不抛即过(证基准测的是合法路径)。 */
class PayloadInputValidatorBenchmarkTest {

    @Test
    void setup_allModes_validatePasses() {
        for (String mode : new String[]{"none", "enumrange", "pattern"}) {
            PayloadInputValidatorBenchmark b = new PayloadInputValidatorBenchmark();
            b.mode = mode;
            b.n = 10;
            b.setup();
            assertThatCode(b::validate).doesNotThrowAnyException();
        }
    }
}
