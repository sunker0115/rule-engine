package com.sstlfsj.rule.conformance;

/**
 * 连接器一致性黄金用例：一条上游桩定义（路径 + 状态 + 响应体）配一条期望（映射值或失败细码）。
 * 成功用例 expectedValue 非 null、expectedErrorCode 为 null；失败用例反之。
 *
 * @param name              用例名（断言失败时定位）
 * @param stubPath          上游桩路径（GET）
 * @param stubStatus        上游桩响应状态码
 * @param stubBody          上游桩响应体（JSON 文本，PARSE 类用例可为非法 JSON）
 * @param valuePath         取值的点号 jsonPath（如 "data.score"）
 * @param expectedValue     期望映射值（成功用例非 null）
 * @param expectedErrorCode 期望 MetricFetchError 名（失败用例非 null），成功为 null
 */
public record GoldenCase(String name, String stubPath, int stubStatus, String stubBody,
                         String valuePath, Object expectedValue, String expectedErrorCode) {

    /** 构造成功用例（断言映射值命中）。 */
    public static GoldenCase success(String name, String stubPath, String stubBody,
                                     String valuePath, Object expectedValue) {
        return new GoldenCase(name, stubPath, 200, stubBody, valuePath, expectedValue, null);
    }

    /** 构造失败用例（断言归一细码命中）。 */
    public static GoldenCase failure(String name, String stubPath, int stubStatus, String stubBody,
                                     String valuePath, String expectedErrorCode) {
        return new GoldenCase(name, stubPath, stubStatus, stubBody, valuePath, null, expectedErrorCode);
    }
}
