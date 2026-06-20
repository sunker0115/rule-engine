package com.sstlfsj.rule.kernel.api.spi.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskRunCallbackTest {

    @Test
    void run_passesTaskIdToImplementation() {
        List<Long> invoked = new ArrayList<>();

        TaskRunCallback callback = invoked::add;

        callback.run(42L);

        assertEquals(1, invoked.size());
        assertEquals(42L, invoked.get(0));
    }
}
