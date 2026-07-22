package com.sstlfsj.rule.kernel.api.model.flow;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;

/**
 * 变换节点：求值 expression，把结果写入 flow 命名空间的 outputKey，供下游 Switch/Transform 通过 flow.{outputKey} 读取。
 *
 * @param id         节点在图内的唯一 id
 * @param lang       表达式引擎
 * @param expression 变换表达式
 * @param outputKey  写入 flow 命名空间的键名
 */
public record TransformNode(String id, ExpressionLang lang, String expression, String outputKey)
        implements FlowNode {}
