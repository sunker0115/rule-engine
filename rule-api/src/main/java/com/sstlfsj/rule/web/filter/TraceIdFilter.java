package com.sstlfsj.rule.web.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 从 MDC 读取 OTel 注入的 traceId，写入 X-Trace-Id 响应头，便于按响应回溯链路日志。 */
@Component
@Order(0)
public class TraceIdFilter implements Filter {

    static final String HEADER_NAME = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResp) {
            String traceId = MDC.get("traceId");
            if (traceId != null) {
                httpResp.setHeader(HEADER_NAME, traceId);
            }
        }
        chain.doFilter(request, response);
    }
}
