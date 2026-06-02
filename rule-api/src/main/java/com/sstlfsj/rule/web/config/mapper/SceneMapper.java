package com.sstlfsj.rule.web.config.mapper;

import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.web.config.dto.SceneResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/** SceneDef → SceneResponse 转换，由 MapStruct 在编译期生成实现类。 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface SceneMapper {

    /** 将 domain 实体转换为 API 响应 DTO。 */
    SceneResponse toResponse(SceneDef scene);
}
