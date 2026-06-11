package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.Tenant;
import org.apache.ibatis.annotations.Mapper;

/** tenant 表只读 Mapper。 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    /** 按 code 查租户（不限 status，code→id 为不可变事实），不存在返回 null。 */
    default Tenant findByCode(String code) {
        return selectOne(new LambdaQueryWrapper<Tenant>().eq(Tenant::getCode, code));
    }
}
