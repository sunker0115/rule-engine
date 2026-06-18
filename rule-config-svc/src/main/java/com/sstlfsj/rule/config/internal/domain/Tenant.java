package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** tenant 表实体。 */
@Getter
@Setter
@TableName("tenant")
public class Tenant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private TenantStatus status;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;
}
