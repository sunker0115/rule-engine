package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** evaluation_session 审计写入专用 JSON 处理器：单字段编码失败降级为 SQL NULL。 */
public class BestEffortJsonTypeHandler extends Jackson3TypeHandler {

    public BestEffortJsonTypeHandler(Class<?> type) {
        super(type);
    }

    public BestEffortJsonTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Object parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            statement.setString(index, toJson(parameter));
        } catch (RuntimeException ex) {
            statement.setNull(index, Types.VARCHAR);
        }
    }
}
