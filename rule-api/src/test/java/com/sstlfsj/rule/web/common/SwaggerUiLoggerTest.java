package com.sstlfsj.rule.web.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class SwaggerUiLoggerTest {

    @Test
    void logSwaggerUi_打印含端口的访问地址() {
        MockEnvironment env = new MockEnvironment().withProperty("server.port", "9090");
        ListAppender<ILoggingEvent> appender = attachAppender();

        new SwaggerUiLogger(env).logSwaggerUi();

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("http://localhost:9090/swagger-ui/index.html"));
    }

    @Test
    void logSwaggerUi_端口未配置时默认8080() {
        ListAppender<ILoggingEvent> appender = attachAppender();

        new SwaggerUiLogger(new MockEnvironment()).logSwaggerUi();

        assertThat(appender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("http://localhost:8080/swagger-ui/index.html"));
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SwaggerUiLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
