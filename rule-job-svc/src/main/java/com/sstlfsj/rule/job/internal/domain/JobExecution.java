package com.sstlfsj.rule.job.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** job_execution 表实体，记录每次 Job 运行结果，对应 05-storage.md §3.10。 */
@Getter
@Setter
@TableName("job_execution")
public class JobExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobDefinitionId;
    private Long tenantId;
    private LocalDateTime triggerAt;
    private String status;
    private Integer subjectCount;
    private Integer successCount;
    private Integer errorCount;
    private String errorSummary;
    private LocalDateTime finishedAt;
}
