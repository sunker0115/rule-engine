package com.sstlfsj.rule.job.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** job_definition 表实体，对应 05-storage.md §3.10 Job DDL。 */
@Getter
@Setter
@TableName("job_definition")
public class JobDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String sceneCode;
    private String code;
    private String name;
    /** Spring 6 段 cron（秒 分 时 日 月 周）。 */
    private String cronExpression;
    /** JSON：主体查询配置，如 {"type":"BEAN_METHOD","ref":"<bean>#<method>"}（D48）。 */
    private String subjectQuery;
    private String eventType;
    /** D49 遗留列，已不再使用——payload 由 @RuleJob 方法返回的 JobTarget.payload 直接携带。 */
    private String payloadTemplate;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}