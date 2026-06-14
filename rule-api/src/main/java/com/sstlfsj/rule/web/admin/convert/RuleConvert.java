package com.sstlfsj.rule.web.admin.convert;

import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/** RuleDefinition → RuleListItemVO 转换。枚举→String、id→ruleDefinitionId 由 MapStruct 编译期生成。 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface RuleConvert {

    @Mapping(target = "ruleDefinitionId", source = "id")
    @Mapping(target = "kind", expression = "java(rd.getKind() != null ? rd.getKind().name() : null)")
    @Mapping(target = "sceneCode", ignore = true)  // controller 层批量回填
    @Mapping(target = "status", expression = "java(rd.getStatus().name())")
    RuleListItemVO toListItemVO(RuleDefinition rd);
}
