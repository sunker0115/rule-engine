package com.sstlfsj.rule.config.api.dto;

/** VALUE kind 的值类型（对齐 kernel DataType，排除 UNKNOWN 哨兵）。 */
public enum ValueDataType { LONG, DOUBLE, DECIMAL, STRING, BOOLEAN, DATE, DATETIME, LIST }
