package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.internal.repository.SubjectQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlSubjectQueryRunnerTest {

    @Mock
    SubjectQueryMapper subjectQueryMapper;

    SqlSubjectQueryRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SqlSubjectQueryRunner(subjectQueryMapper, JsonMapper.builder().build());
    }

    @Test
    void passesSqlThroughAndReturnsSubjectRows() {
        String sql = "SELECT user_id AS subjectId FROM t";
        when(subjectQueryMapper.runSql(sql)).thenReturn(List.of(Map.of("subjectId", "u1")));
        List<Map<String, Object>> rows = runner.query("{\"type\":\"SQL\",\"sql\":\"" + sql + "\"}");
        assertEquals(1, rows.size());
        assertEquals("u1", rows.get(0).get("subjectId"));
    }

    @Test
    void rejectsNonSqlType() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.query("{\"type\":\"EXTERNAL_HTTP\"}"));
    }

    @Test
    void rejectsResultMissingSubjectIdColumn() {
        String sql = "SELECT id FROM t";
        when(subjectQueryMapper.runSql(sql)).thenReturn(List.of(Map.of("id", "u1")));
        assertThrows(IllegalArgumentException.class,
                () -> runner.query("{\"type\":\"SQL\",\"sql\":\"" + sql + "\"}"));
    }

    @Test
    void rejectsBlankConfig() {
        assertThrows(IllegalArgumentException.class, () -> runner.query(""));
    }

    @Test
    void rejectsBlankSql() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.query("{\"type\":\"SQL\",\"sql\":\"\"}"));
    }
}
