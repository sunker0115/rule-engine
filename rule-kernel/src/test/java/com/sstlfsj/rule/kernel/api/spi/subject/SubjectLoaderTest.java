package com.sstlfsj.rule.kernel.api.spi.subject;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubjectLoaderTest {

    private static final SubjectLoader STUB = new SubjectLoader() {
        @Override
        public Subject load(String subjectId, SubjectType subjectType, RuleEvent event) {
            return new Subject(subjectId, subjectType, Map.of());
        }

        @Override
        public List<SubjectType> supportedTypes() {
            return List.of(SubjectType.USER);
        }
    };

    private static RuleEvent buildEvent() {
        return new RuleEvent("t1", "SCENE1", "PAYMENT",
                "u1", "e1", Instant.now(), Map.of(), Map.of());
    }

    @Test
    void load_returnsSubjectWithMatchingId() {
        Subject subject = STUB.load("u1", SubjectType.USER, buildEvent());
        assertEquals("u1", subject.subjectId());
        assertEquals(SubjectType.USER, subject.subjectType());
    }

    @Test
    void supportedTypes_returnsNonEmptyList() {
        List<SubjectType> types = STUB.supportedTypes();
        assertFalse(types.isEmpty());
        assertTrue(types.contains(SubjectType.USER));
    }
}
