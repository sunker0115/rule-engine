package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/** scene 表 MyBatis-Plus Mapper。 */
@Mapper
public interface SceneMapper extends BaseMapper<SceneDef> {

    /** 按 (tenantId, code) 查 Scene，不存在返回 null。 */
    default SceneDef findByCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<SceneDef>()
                .eq(SceneDef::getTenantId, tenantId)
                .eq(SceneDef::getCode, code));
    }

    /** 按 id 集合批量查 Scene；空集合返回空列表。 */
    default List<SceneDef> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<SceneDef>().in(SceneDef::getId, ids));
    }

    /** 按 (tenantId) + code 集合批量查 Scene；空集合返回空列表。 */
    default List<SceneDef> findByCodes(Long tenantId, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<SceneDef>()
                .eq(SceneDef::getTenantId, tenantId)
                .in(SceneDef::getCode, codes));
    }
}
