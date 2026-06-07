package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CreateJobRequest @NotBlank / @NotNull 约束校验测试。 */
class CreateJobRequestTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private CreateJobRequest valid() {
        return new CreateJobRequest("t1", "fraud", "j1", "Job1", "0 0 0 * * *",
                Map.of("type", "SQL", "sql", "SELECT 1 AS subjectId"), "login", null);
    }

    @Test
    void validRequestPassesValidation() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void blankTenantIdFailsValidation() {
        var req = new CreateJobRequest("", "fraud", "j1", "Job1", "0 0 0 * * *",
                Map.of("type", "SQL"), "login", null);
        Set<ConstraintViolation<CreateJobRequest>> v = validator.validate(req);
        assertThat(v).anyMatch(c -> c.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void nullSubjectQueryFailsValidation() {
        var req = new CreateJobRequest("t1", "fraud", "j1", "Job1", "0 0 0 * * *",
                null, "login", null);
        Set<ConstraintViolation<CreateJobRequest>> v = validator.validate(req);
        assertThat(v).anyMatch(c -> c.getPropertyPath().toString().equals("subjectQuery"));
    }

    @Test
    void blankEventTypeFailsValidation() {
        var req = new CreateJobRequest("t1", "fraud", "j1", "Job1", "0 0 0 * * *",
                Map.of("type", "SQL"), "  ", null);
        Set<ConstraintViolation<CreateJobRequest>> v = validator.validate(req);
        assertThat(v).anyMatch(c -> c.getPropertyPath().toString().equals("eventType"));
    }
}
