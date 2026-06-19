package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.SubjectQuery;
import com.sstlfsj.rule.job.api.SubjectTarget;

import java.util.function.Consumer;

/** 主体集合查询 SPI：按 typed {@link SubjectQuery} 配置逐个把目标推给 sink。 */
public interface SubjectQueryRunner {

    /**
     * 执行主体查询，逐个把 {@link SubjectTarget} 推给 {@code sink}（push 风格，统一两种来源形态）：
     * <ul>
     *   <li>无参方法返回 {@code List<SubjectTarget>} —— 小数据量，一次性内存集合；</li>
     *   <li>单 {@code SubjectPage} 参方法返回 {@code List<SubjectTarget>} —— 分页拉取（仿 ElasticJob DataflowJob），
     *       框架 page 0、1、2… 反复拉到空批为止，每批只占一页内存，支持大数据量。</li>
     * </ul>
     * 分页循环由实现内部负责，调用方只管处理每个目标。
     *
     * @param subjectQuery 主体查询配置（typed，按具体子类型分发）
     * @param sink         目标消费者（合成事件 + 注入）
     */
    void forEachTarget(SubjectQuery subjectQuery, Consumer<SubjectTarget> sink);
}
