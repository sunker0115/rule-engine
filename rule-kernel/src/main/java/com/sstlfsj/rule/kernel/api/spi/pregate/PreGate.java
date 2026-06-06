package com.sstlfsj.rule.kernel.api.spi.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;

/** 规则评估前执行的前置门控检查 SPI 接口（如灰度发布、限流、白名单）。 */
public interface PreGate {
    /**
     * 返回本门控处理的类型标识，用于与规则配置中的 gateType 匹配。
     *
     * @return 门控类型标识
     */
    String gateType();

    /**
     * 执行前置门控检查。
     *
     * @param ctx 门控上下文
     * @return 门控结果（是否放行及拦截原因）
     */
    PreGateResult evaluate(PreGateContext ctx);
}
