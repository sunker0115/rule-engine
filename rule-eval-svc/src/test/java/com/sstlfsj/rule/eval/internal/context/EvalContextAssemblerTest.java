package com.sstlfsj.rule.eval.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvalContextAssemblerTest {

    @Test
    void assemble_noSubjectLoader_usesEmptySubject() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());

        EvalContext ctx = assembler.assemble(event, List.of());

        assertNotNull(ctx.getSubject());
        assertEquals("u1", ctx.getSubject().subjectId());
        assertEquals("1", ctx.getTenantId());
    }

    @Test
    void assemble_providedMetrics_usedWhenAvailable() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(),
                Map.of("score", 95));

        EvalContext ctx = assembler.assemble(event, List.of());

        MetricValue scoreMetric = ctx.getMetric("score");
        assertNotNull(scoreMetric);
        assertEquals(95, scoreMetric.value());
        assertEquals("PROVIDED", scoreMetric.valueSource());
    }

    @Test
    void assemble_withSubjectLoader_callsLoader() {
        SubjectLoader loader = mock(SubjectLoader.class);
        Subject subject = new Subject("u1", SubjectType.USER, Map.of("age", 25));
        when(loader.supportedTypes()).thenReturn(List.of(SubjectType.USER));
        when(loader.load(eq("u1"), eq(SubjectType.USER), any())).thenReturn(subject);

        EvalContextAssembler assembler = new EvalContextAssembler(List.of(loader), List.of());
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());

        EvalContext ctx = assembler.assemble(event, List.of());

        assertEquals(subject, ctx.getSubject());
        assertEquals(25, ctx.getSubject().getAttribute("age"));
    }
}
