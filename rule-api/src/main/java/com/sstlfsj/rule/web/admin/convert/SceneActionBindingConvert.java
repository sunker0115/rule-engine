package com.sstlfsj.rule.web.admin.convert;

import com.sstlfsj.rule.config.api.service.SceneActionBindingService.SceneActionBindingItem;
import com.sstlfsj.rule.web.admin.dto.ActionBindingItemDto;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/** ActionBindingItemDto ↔ SceneActionBindingItem 转换，由 MapStruct 在编译期生成实现类。 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface SceneActionBindingConvert {

    /** 请求 DTO → service 项。 */
    SceneActionBindingItem toItem(ActionBindingItemDto dto);

    /** service 项 → 响应 DTO。 */
    ActionBindingItemDto toDto(SceneActionBindingItem item);

    /** 请求 DTO 列表 → service 项列表。 */
    List<SceneActionBindingItem> toItems(List<ActionBindingItemDto> dtos);

    /** service 项列表 → 响应 DTO 列表。 */
    List<ActionBindingItemDto> toDtos(List<SceneActionBindingItem> items);
}
