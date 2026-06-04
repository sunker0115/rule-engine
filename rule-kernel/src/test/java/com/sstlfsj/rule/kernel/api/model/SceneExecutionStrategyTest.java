package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneExecutionStrategyTest {

    @Test
    void allValuesExist() {
        assertNotNull(SceneExecutionStrategy.valueOf("HIGHEST_PRIORITY"));
        assertNotNull(SceneExecutionStrategy.valueOf("ALL_HITS"));
        assertNotNull(SceneExecutionStrategy.valueOf("FIRST_HIT"));
    }

    @Test
    void threeValues() {
        assertEquals(3, SceneExecutionStrategy.values().length);
    }
}
