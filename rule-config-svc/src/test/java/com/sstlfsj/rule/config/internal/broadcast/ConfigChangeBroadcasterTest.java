package com.sstlfsj.rule.config.internal.broadcast;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.*;

class ConfigChangeBroadcasterTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Scheduler> provider(Scheduler s) {
        ObjectProvider<Scheduler> p = mock(ObjectProvider.class);
        doAnswer(inv -> {
            ((java.util.function.Consumer<Scheduler>) inv.getArgument(0)).accept(s);
            return null;
        }).when(p).ifAvailable(any());
        return p;
    }

    @Test
    void sceneChangedTriggersBroadcastWithSceneParam() {
        Scheduler scheduler = mock(Scheduler.class);
        ConfigChangeBroadcaster b = new ConfigChangeBroadcaster(provider(scheduler));
        b.onSceneChanged(new SceneChangedEvent("9100", "fraud_check", true));
        verify(scheduler).triggerBroadcast("config-change", "scene:9100:fraud_check:true");
    }

    @Test
    void rulePublishedTriggersBroadcastWithRuleParam() {
        Scheduler scheduler = mock(Scheduler.class);
        ConfigChangeBroadcaster b = new ConfigChangeBroadcaster(provider(scheduler));
        b.onRulePublished(new RulePublishedEvent("9100", "fraud_check", 42L));
        verify(scheduler).triggerBroadcast("config-change", "rule:9100:fraud_check");
    }

    @Test
    void noSchedulerDoesNotThrow() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Scheduler> empty = mock(ObjectProvider.class);
        ConfigChangeBroadcaster b = new ConfigChangeBroadcaster(empty);
        b.onSceneChanged(new SceneChangedEvent("9100", "fraud_check", true));
    }
}
