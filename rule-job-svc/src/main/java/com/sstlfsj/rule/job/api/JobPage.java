package com.sstlfsj.rule.job.api;

/**
 * 分页拉取上下文（仿 ElasticJob DataflowJob）：框架从 0 起递增 {@code pageNumber} 反复调用
 * {@code @RuleJob} 分页方法，直到方法返回空批为止；每批处理完再拉下一批，内存只占一批。
 *
 * <p>分页方法签名形如 {@code List<JobTarget> m(JobPage page)}，方法体用 {@code page.offset()} /
 * {@code page.pageSize()} 作 SQL {@code LIMIT ... OFFSET ...}。
 *
 * @param pageNumber 页码，从 0 开始递增
 * @param pageSize   框架建议的每页条数（方法可据此 LIMIT）
 */
public record JobPage(int pageNumber, int pageSize) {

    /** 偏移量 = pageNumber * pageSize，便于直接做 SQL OFFSET。 */
    public long offset() {
        return (long) pageNumber * pageSize;
    }
}
