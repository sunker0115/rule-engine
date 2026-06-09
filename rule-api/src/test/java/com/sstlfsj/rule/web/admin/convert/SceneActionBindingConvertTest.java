package com.sstlfsj.rule.web.admin.convert;

import com.sstlfsj.rule.config.api.service.SceneActionBindingService.SceneActionBindingItem;
import com.sstlfsj.rule.web.admin.dto.ActionBindingItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 MapStruct 生成的 SceneActionBindingConvert 在 DTO ↔ service 项间正确映射。 */
class SceneActionBindingConvertTest {

    private final SceneActionBindingConvert convert = new SceneActionBindingConvertImpl();

    @Test
    void toItem_mapsActionTypeAndParams() {
        ActionBindingItemDto dto = new ActionBindingItemDto("BLOCK_TX", Map.of("reason", "risk"));

        SceneActionBindingItem item = convert.toItem(dto);

        assertThat(item.actionType()).isEqualTo("BLOCK_TX");
        assertThat(item.defaultParams()).isEqualTo(Map.of("reason", "risk"));
    }

    @Test
    void toDto_mapsActionTypeAndParams() {
        SceneActionBindingItem item = new SceneActionBindingItem("SEND_ALERT", Map.of("channel", "sms"));

        ActionBindingItemDto dto = convert.toDto(item);

        assertThat(dto.actionType()).isEqualTo("SEND_ALERT");
        assertThat(dto.defaultParams()).isEqualTo(Map.of("channel", "sms"));
    }

    @Test
    void toItems_mapsList() {
        List<SceneActionBindingItem> items = convert.toItems(List.of(
                new ActionBindingItemDto("A", null),
                new ActionBindingItemDto("B", Map.of("k", 1))));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).actionType()).isEqualTo("A");
        assertThat(items.get(0).defaultParams()).isNull();
        assertThat(items.get(1).defaultParams()).isEqualTo(Map.of("k", 1));
    }

    @Test
    void nullInput_returnsNull() {
        assertThat(convert.toItem(null)).isNull();
        assertThat(convert.toDto(null)).isNull();
    }
}
