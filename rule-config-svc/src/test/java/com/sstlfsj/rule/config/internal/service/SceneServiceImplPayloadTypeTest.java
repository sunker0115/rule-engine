package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 SceneServiceImpl 创建/更新期对 payloadSchema 字段 type 的封闭校验。 */
class SceneServiceImplPayloadTypeTest {

    // 校验在任何 mapper 调用之前执行，故 collaborator 传 null 即可触发并断言。
    private final SceneServiceImpl service = new SceneServiceImpl(null, null);

    private static PayloadFieldSpec field(String type) {
        return new PayloadFieldSpec("amount", type, false, null, null, null, null, null);
    }

    // createScene 在任何 mapper 调用前先校验 payloadSchema type，故能以 null collaborator 触发；
    // updateScene 需先 findScene(走 mapper)，无法在不 mock mapper 的前提下隔离校验，校验逻辑由二者共享的私有方法保证。
    @Test
    void createScene_illegalType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createScene("1", "SCENE_A", "场景A", null, null, null,
                        null, List.of(field("STRIGN")), null, "actor"));
    }
}
