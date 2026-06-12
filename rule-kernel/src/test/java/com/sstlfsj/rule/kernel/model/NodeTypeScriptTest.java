package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.NodeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeScriptTest {
    @Test
    void scriptTagIsStable() {
        assertThat(NodeType.SCRIPT.tag()).isEqualTo("ScriptNode");
    }
}
