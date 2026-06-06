package com.sstlfsj.rule.kernel.api.spi.subject;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;

import java.util.List;

/** 为规则评估加载主体实体数据的 SPI 接口。 */
public interface SubjectLoader {
    /**
     * 加载规则评估所需的主体实体数据。
     *
     * @param subjectId   主体 ID
     * @param subjectType 主体类型
     * @param event       触发本次评估的规则事件
     * @return 装配好的主体对象
     */
    Subject load(String subjectId, SubjectType subjectType, RuleEvent event);

    /**
     * 返回本加载器支持的主体类型列表。
     *
     * @return 支持的主体类型集合
     */
    List<SubjectType> supportedTypes();
}
