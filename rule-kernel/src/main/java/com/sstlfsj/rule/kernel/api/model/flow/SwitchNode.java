package com.sstlfsj.rule.kernel.api.model.flow;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;

import java.util.List;

/**
 * 分支节点：求值 expression，按结果值匹配出边的 caseKey；无匹配走 default 边（caseKey=null 的出边）。
 * expression 可读 flow 命名空间（上游 Transform 产出、上一步 RuleRef 结果）。
 *
 * @param id         节点在图内的唯一 id
 * @param lang       表达式引擎
 * @param expression 分支表达式
 * @param caseKeys   合法分支键集合（出边 caseKey 须属此集）
 */
public record SwitchNode(String id, ExpressionLang lang, String expression, List<String> caseKeys)
        implements FlowNode {

    public SwitchNode {
        caseKeys = caseKeys == null ? List.of() : List.copyOf(caseKeys);
    }
}
