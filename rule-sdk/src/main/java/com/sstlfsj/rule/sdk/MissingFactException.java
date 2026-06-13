package com.sstlfsj.rule.sdk;

import java.lang.reflect.Parameter;

/** required 的 @Fact 在 payload 与元数据中都取不到值时抛出。 */
public class MissingFactException extends RuntimeException {
    public MissingFactException(String factName, Parameter param) {
        super("必填 @Fact \"" + factName + "\" 取值为空(payload/元数据均无): " + param);
    }
}
