package com.sstlfsj.rule.sdk;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.*;

class FactResolverCoerceTest {

    enum Color { RED, GREEN }

    static class Holder {
        public void m(Integer i, Long l, Boolean b, Color c) {}
    }

    private static Object coerce(Object v, Class<?> t) throws Exception {
        Method m = FactResolver.class.getDeclaredMethod("coerce", Object.class, Class.class);
        m.setAccessible(true);
        return m.invoke(null, v, t);
    }

    @Test
    void coerces_stringLiterals_toTargetTypes() throws Exception {
        assertThat(coerce("7", Integer.class)).isEqualTo(7);
        assertThat(coerce("9", Long.class)).isEqualTo(9L);
        assertThat(coerce("true", Boolean.class)).isEqualTo(true);
        assertThat(coerce("RED", Color.class)).isEqualTo(Color.RED);
    }

    @Test
    void invalidString_throwsIllegalArgument() {
        assertThatThrownBy(() -> coerce("notInt", Integer.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
