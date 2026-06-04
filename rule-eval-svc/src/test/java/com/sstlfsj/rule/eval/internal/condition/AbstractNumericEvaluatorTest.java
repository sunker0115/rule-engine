package com.sstlfsj.rule.eval.internal.condition;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 AbstractNumericEvaluator.toNumber 的类型转换逻辑。 */
class AbstractNumericEvaluatorTest {

    @Test void toNumber_long_returnsSelf()       { assertEquals(42L, AbstractNumericEvaluator.toNumber(42L)); }
    @Test void toNumber_integer_returnsInt()     { assertEquals(7, AbstractNumericEvaluator.toNumber(7)); }
    @Test void toNumber_stringLong_parsed()      { assertEquals(42L, AbstractNumericEvaluator.toNumber("42")); }
    @Test void toNumber_stringDouble_parsed()    { assertEquals(3.14, AbstractNumericEvaluator.toNumber("3.14").doubleValue(), 1e-9); }
    @Test void toNumber_nonNumeric_returnsNull() { assertNull(AbstractNumericEvaluator.toNumber("abc")); }
    @Test void toNumber_null_returnsNull()       { assertNull(AbstractNumericEvaluator.toNumber(null)); }
}
