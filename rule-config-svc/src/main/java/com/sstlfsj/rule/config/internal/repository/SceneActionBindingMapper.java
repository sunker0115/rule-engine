package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.SceneActionBindingDef;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** scene_action_binding 表 MyBatis-Plus Mapper。 */
@Mapper
public interface SceneActionBindingMapper extends BaseMapper<SceneActionBindingDef> {

    /** 按 sceneId 查该场景全部 action 绑定。 */
    default List<SceneActionBindingDef> findBySceneId(Long sceneId) {
        return selectList(new LambdaQueryWrapper<SceneActionBindingDef>()
                .eq(SceneActionBindingDef::getSceneId, sceneId));
    }

    /** 删除指定场景下某 actionType 的绑定。 */
    default int deleteBySceneIdAndActionType(Long sceneId, String actionType) {
        return delete(new LambdaQueryWrapper<SceneActionBindingDef>()
                .eq(SceneActionBindingDef::getSceneId, sceneId)
                .eq(SceneActionBindingDef::getActionType, actionType));
    }
}
