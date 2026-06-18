package com.sstlfsj.rule.kernel.api.spi.metric;

/**
 * 取数分阶段 trace 收集器：handler 在取数编排各阶段回调记录可观测信息（自助测试用）。
 * <p>正常取数路径传 {@link #noop()}（不采集，零开销）；自助测试路径传一个累积实现，
 * 取数后读出各阶段字段组装 trace。各方法允许被同一次取数多次调用，后写覆盖前写。</p>
 */
public interface FetchTraceCollector {

    /**
     * 记录 HTTP 渲染后的请求文本（method url headers body）。
     *
     * @param renderedRequest 渲染后的请求描述
     */
    void renderedRequest(String renderedRequest);

    /**
     * 记录 SQL 绑定后的语句。
     *
     * @param boundSql 命名参数绑定后的 SQL
     */
    void boundSql(String boundSql);

    /**
     * 记录原始响应（HTTP 响应体 / SQL 原始首行文本）。
     *
     * @param rawResponse 原始响应文本
     */
    void rawResponse(String rawResponse);

    /**
     * 记录 HTTP successWhen 判定结果。
     *
     * @param successMatched 是否命中成功谓词
     */
    void successMatched(boolean successMatched);

    /**
     * 记录映射/强转后的最终值。
     *
     * @param mappedValue 映射值
     */
    void mappedValue(Object mappedValue);

    /**
     * 记录命中的 MetricFetchError 名（失败路径）。
     *
     * @param errorCode 错误码名
     */
    void errorCode(String errorCode);

    /** @return 不采集任何信息的空收集器，供正常取数路径复用同一编排而无 trace 开销。 */
    static FetchTraceCollector noop() {
        return Noop.INSTANCE;
    }

    /** 空实现：所有记录调用均忽略。 */
    final class Noop implements FetchTraceCollector {
        private static final Noop INSTANCE = new Noop();

        private Noop() {}

        @Override public void renderedRequest(String renderedRequest) {}
        @Override public void boundSql(String boundSql) {}
        @Override public void rawResponse(String rawResponse) {}
        @Override public void successMatched(boolean successMatched) {}
        @Override public void mappedValue(Object mappedValue) {}
        @Override public void errorCode(String errorCode) {}
    }
}
