package com.sstlfsj.rule.web.common;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 应用就绪后在日志打印 Swagger UI 访问地址，便于本地调试快速进入接口文档。 */
@Component
@RequiredArgsConstructor
public class SwaggerUiLogger {

    private static final Logger log = LoggerFactory.getLogger(SwaggerUiLogger.class);

    private final Environment environment;

    /** 上下文就绪后打印 Swagger UI 地址；端口取 server.port，未配置时默认 8080。 */
    @EventListener(ApplicationReadyEvent.class)
    public void logSwaggerUi() {
        String port = environment.getProperty("server.port", "8080");
        log.info("Swagger UI: http://localhost:{}/swagger-ui/index.html", port);
    }
}
