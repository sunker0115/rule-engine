package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubjectTest {

    @Test
    void attributes_areImmutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("level", 5);
        Subject subject = new Subject("u1", SubjectType.USER, mutable);
        mutable.put("extra", "x");
        assertEquals(1, subject.attributes().size(), "构造后修改原始 map 不应影响 Subject");
    }

    @Test
    void attributes_mapIsUnmodifiable() {
        Subject subject = new Subject("u1", SubjectType.USER, Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> subject.attributes().put("k", "v"));
    }

    @Test
    void getAttribute_returnsValue() {
        Subject subject = new Subject("u1", SubjectType.USER, Map.of("level", 3));
        assertEquals(3, subject.getAttribute("level"));
    }

    @Test
    void getAttribute_returnsNullForMissingKey() {
        Subject subject = new Subject("u1", SubjectType.USER, Map.of());
        assertNull(subject.getAttribute("missing"));
    }

    @Test
    void recordEquality_byValue() {
        Subject a = new Subject("u1", SubjectType.ACCOUNT, Map.of("k", "v"));
        Subject b = new Subject("u1", SubjectType.ACCOUNT, Map.of("k", "v"));
        assertEquals(a, b);
    }
}
