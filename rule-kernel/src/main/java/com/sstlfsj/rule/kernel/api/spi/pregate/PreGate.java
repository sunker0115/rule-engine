package com.sstlfsj.rule.kernel.api.spi.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;

/** 规则评估前执行的前置门控检查 SPI 接口（如灰度发布、限流、白名单）。 */
public interface PreGate {
    String gateType();
    PreGateResult evaluate(PreGateContext ctx);
}
