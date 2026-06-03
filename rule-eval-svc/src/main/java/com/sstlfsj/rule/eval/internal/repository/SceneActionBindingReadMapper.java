package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** scene_action_binding 只读 Mapper，按 sceneCode 查询该场景下所有 ActionHandler 绑定。 */
@Mapper
public interface SceneActionBindingReadMapper {

    /**
     * 查询指定租户和场景下的所有 Action 绑定。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return Action 绑定列表，场景无绑定时返回空列表
     */
    @Select("""
            SELECT sab.action_type AS actionType, sab.default_params AS defaultParamsJson
            FROM scene_action_binding sab
            JOIN scene s ON sab.scene_id = s.id
            WHERE s.tenant_id = #{tenantId} AND s.code = #{sceneCode}
            """)
    List<SceneActionBindingRow> findBySceneCode(@Param("tenantId") Long tenantId,
                                                @Param("sceneCode") String sceneCode);
}
