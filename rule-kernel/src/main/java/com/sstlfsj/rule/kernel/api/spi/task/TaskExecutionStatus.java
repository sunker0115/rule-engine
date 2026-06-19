package com.sstlfsj.rule.kernel.api.spi.task;

/** 单次任务执行的终态(name() == DB 持久化字面量)。 */
public enum TaskExecutionStatus { RUNNING, SUCCESS, PARTIAL_FAIL, FAILED }
