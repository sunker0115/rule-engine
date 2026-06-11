package com.sstlfsj.rule.job.internal.domain;

/** job_execution.status 取值：单次 Job 运行终态（PARTIAL_FAIL=部分主体失败）。 */
public enum JobExecutionStatus { RUNNING, SUCCESS, PARTIAL_FAIL, FAILED }