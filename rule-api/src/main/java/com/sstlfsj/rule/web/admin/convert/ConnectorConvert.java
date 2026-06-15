package com.sstlfsj.rule.web.admin.convert;

import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorView;
import com.sstlfsj.rule.web.admin.dto.ConnectorResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/** 连接器视图 → API 响应 DTO 转换。 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ConnectorConvert {

    /** 将 service 列表视图转换为 API 响应 DTO。 */
    ConnectorResponse toResponse(ConnectorView view);
}
