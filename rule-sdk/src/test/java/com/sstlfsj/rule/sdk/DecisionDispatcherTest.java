package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionDispatcherTest {

    private EvalResult resultWith(Decision... ds) {
        return new EvalResult(true, ds.length > 0 ? ds[0] : null, List.of(ds),
                List.of(), null, null, null, null);
    }

    @Test
    void dispatch_perHitDecision_toAllSinks_andIsolatesSinkFailure() {
        List<String> seenA = new ArrayList<>();
        DecisionSink ok = e -> seenA.add(e.decisionCode());
        DecisionSink boom = e -> { throw new RuntimeException("x"); };

        DecisionDispatcher d = new DecisionDispatcher(List.of(boom, ok));
        EvalResult r = resultWith(
                new Decision("REVIEW", "复核", 50, 1L),
                new Decision("LOG", "记录", 10, 1L));

        d.onEvaluated(null, r, null);

        // boom 抛异常被吞,不影响 ok;两个决策都派发
        assertThat(seenA).containsExactly("REVIEW", "LOG");
    }

    @Test
    void dispatch_emptyHits_noop() {
        List<String> seen = new ArrayList<>();
        DecisionDispatcher d = new DecisionDispatcher(List.of(e -> seen.add(e.decisionCode())));
        d.onEvaluated(null, EvalResult.miss(), null);
        d.onEvaluated(null, null, null);
        assertThat(seen).isEmpty();
    }
}
