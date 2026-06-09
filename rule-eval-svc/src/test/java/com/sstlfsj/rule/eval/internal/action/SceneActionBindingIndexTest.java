package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingFullRow;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** SceneActionBindingIndex 单测：启动全量分组载入、default_params 解析、缺省空列表、场景变更刷新/移除。 */
class SceneActionBindingIndexTest {

    private SceneActionBindingIndex newIndex(SceneActionBindingReadMapper mapper) {
        return new SceneActionBindingIndex(mapper, JsonMapper.builder().build());
    }

    @Test
    void get_absentScene_returnsEmptyList() {
        SceneActionBindingReadMapper mapper = mock(SceneActionBindingReadMapper.class);
        SceneActionBindingIndex index = newIndex(mapper);

        assertThat(index.get(1L, "nope")).isEmpty();
        verifyNoInteractions(mapper);   // 缺省读纯内存，不触发 DB
    }

    @Test
    void onApplicationReady_loadsAllGroupedByScene_andParsesDefaultParams() {
        SceneActionBindingReadMapper mapper = mock(SceneActionBindingReadMapper.class);
        when(mapper.findAll()).thenReturn(List.of(
                new SceneActionBindingFullRow(1L, "fraud", "BLOCK_TRANSACTION", "{\"reason\":\"risk\"}"),
                new SceneActionBindingFullRow(1L, "fraud", "SEND_ALERT", "{}"),
                new SceneActionBindingFullRow(2L, "login", "SEND_ALERT", null)));
        SceneActionBindingIndex index = newIndex(mapper);

        index.onApplicationReady();

        assertThat(index.get(1L, "fraud"))
                .extracting(SceneActionBindingRow::actionType)
                .containsExactlyInAnyOrder("BLOCK_TRANSACTION", "SEND_ALERT");
        // JSON 串在装载时解析为 Map；null → 空 Map
        assertThat(index.get(1L, "fraud")).filteredOn(r -> r.actionType().equals("BLOCK_TRANSACTION"))
                .first().extracting(SceneActionBindingRow::defaultParams)
                .isEqualTo(Map.of("reason", "risk"));
        assertThat(index.get(2L, "login")).first()
                .extracting(SceneActionBindingRow::defaultParams).isEqualTo(Map.of());
        assertThat(index.get(1L, "login")).isEmpty();
    }

    @Test
    void onSceneChanged_enabled_reloadsSceneBindings() {
        SceneActionBindingReadMapper mapper = mock(SceneActionBindingReadMapper.class);
        when(mapper.findBySceneCode(1L, "fraud"))
                .thenReturn(List.of(new SceneActionBindingFullRow(1L, "fraud", "BLOCK_TRANSACTION", "{\"k\":1}")));
        SceneActionBindingIndex index = newIndex(mapper);

        index.onSceneChanged(new SceneChangedEvent("1", "fraud", true));

        assertThat(index.get(1L, "fraud"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.actionType()).isEqualTo("BLOCK_TRANSACTION");
                    assertThat(r.defaultParams()).isEqualTo(Map.of("k", 1));
                });
    }

    @Test
    void onSceneChanged_disabled_removesSceneBindings() {
        SceneActionBindingReadMapper mapper = mock(SceneActionBindingReadMapper.class);
        when(mapper.findAll()).thenReturn(List.of(
                new SceneActionBindingFullRow(1L, "fraud", "BLOCK_TRANSACTION", null)));
        SceneActionBindingIndex index = newIndex(mapper);
        index.onApplicationReady();
        assertThat(index.get(1L, "fraud")).isNotEmpty();

        index.onSceneChanged(new SceneChangedEvent("1", "fraud", false));

        assertThat(index.get(1L, "fraud")).isEmpty();
        verify(mapper, never()).findBySceneCode(anyLong(), anyString());   // 禁用只移除，不查库
    }
}
