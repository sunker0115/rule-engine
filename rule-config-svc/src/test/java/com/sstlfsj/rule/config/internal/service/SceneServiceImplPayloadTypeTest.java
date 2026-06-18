package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 SceneServiceImpl 创建/更新期对 payloadSchema 字段 type 的封闭校验。 */
class SceneServiceImplPayloadTypeTest {

    // 校验在任何 mapper 调用之前执行，故 collaborator 传 null 即可触发并断言。
    private final SceneServiceImpl service = new SceneServiceImpl(null, null, null);

    private static PayloadFieldSpec field(String type) {
        return new PayloadFieldSpec("amount", type, false, null, null, null, null, null);
    }

    // createScene 在任何 mapper 调用前先校验 payloadSchema type，故能以 null collaborator 触发；
    // updateScene 需先 findScene(走 mapper)，无法在不 mock mapper 的前提下隔离校验，校验逻辑由二者共享的私有方法保证。
    @Test
    void createScene_illegalType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createScene(1L, "SCENE_A", "场景A", null, null, null,
                        null, List.of(field("STRIGN")), null, "actor"));
    }

    @Test
    void createScene_illegalTimezone_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createScene(1L, "SCENE_A", "场景A", null, null, null,
                        null, List.of(), Map.of("timezone", "Asia/Xxx"), "actor"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("timezone"));
    }

    @Test
    void createScene_validTimezone_passesValidation() {
        // 合法时区不应在校验处抛 IllegalArgumentException；后续 null mapper.insert 会 NPE，
        // 故断言抛出的不是带 timezone 文案的 IllegalArgumentException（校验已放行）。
        try {
            service.createScene(1L, "SCENE_A", "场景A", null, null, null,
                    null, List.of(), Map.of("timezone", "Asia/Shanghai"), "actor");
        } catch (IllegalArgumentException e) {
            org.junit.jupiter.api.Assertions.assertFalse(e.getMessage() != null
                    && e.getMessage().contains("timezone"));
        } catch (RuntimeException ignored) {
            // null mapper 引发的 NPE 等属预期，说明校验已通过
        }
    }
}
