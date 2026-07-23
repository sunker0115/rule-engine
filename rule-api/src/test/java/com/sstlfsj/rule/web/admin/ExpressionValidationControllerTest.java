package com.sstlfsj.rule.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.internal.expression.ExpressionValidationService;
import com.sstlfsj.rule.web.admin.dto.ValidateExpressionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExpressionValidationControllerTest {

    private MockMvc mockMvc;
    private ExpressionValidationService validationService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validationService = mock(ExpressionValidationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ExpressionValidationController(validationService)).build();
    }

    @Test
    void validate_ok_returns200() throws Exception {
        when(validationService.validate(1L, "S1", "CEL", "metrics.m1 > 0")).thenReturn(null);

        mockMvc.perform(post("/admin/v1/expressions/validate")
                        .contentType("application/json")
                        .content(om.writeValueAsString(new ValidateExpressionRequest(1L, "S1", "CEL", "metrics.m1 > 0"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.error").doesNotExist());
    }

    @Test
    void validate_error_returns200_withError() throws Exception {
        when(validationService.validate(1L, "S1", "CEL", "metrics.x > 'hello'"))
                .thenReturn("CEL 类型检查失败: expected int, found string");

        mockMvc.perform(post("/admin/v1/expressions/validate")
                        .contentType("application/json")
                        .content(om.writeValueAsString(new ValidateExpressionRequest(1L, "S1", "CEL", "metrics.x > 'hello'"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.error").value("CEL 类型检查失败: expected int, found string"));
    }

    @Test
    void missingRequiredField_returns400() throws Exception {
        mockMvc.perform(post("/admin/v1/expressions/validate")
                        .contentType("application/json")
                        .content("{\"tenantId\": 1}"))
                .andExpect(status().isBadRequest());
    }
}
