package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

/** 规则来源 SPI：将规则快照装载到评估索引。 */
public interface RuleSource {
    /**
     * 将本来源持有的规则快照写入索引。
     * 实现须幂等：同一 ruleVersionId 重复写入不产生重复条目。
     *
     * @param index 目标评估索引
     */
    void loadInto(SceneRuleIndex index);
}
