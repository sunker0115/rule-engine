package com.sstlfsj.rule.web.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 从 X-Actor-Id header 提取操作人身份，放入 ThreadLocal 供 Service 层审计使用。
 * D14：引擎不维护用户表，身份来自上游网关 header。
 */
@Component
@Order(1)
public class ActorIdFilter implements Filter {

    private static final ThreadLocal<String> ACTOR_HOLDER = new ThreadLocal<>();

    /** @return 当前请求的操作人 ID，未传 header 时为 "UNKNOWN" */
    public static String current() {
        return ACTOR_HOLDER.get();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String actorId = httpReq.getHeader("X-Actor-Id");
        ACTOR_HOLDER.set(actorId != null ? actorId : "UNKNOWN");
        try {
            chain.doFilter(request, response);
        } finally {
            ACTOR_HOLDER.remove();
        }
    }
}
