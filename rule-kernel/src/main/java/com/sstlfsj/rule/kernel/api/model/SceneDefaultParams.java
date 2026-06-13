package com.sstlfsj.rule.kernel.api.model;

/**
 * scene 级默认参数(scene.default_params)的键常量,单一真相源,杜绝魔法串。
 * 当前仅 timezone 有消费者(时间类算子兜底时区);currency 等将来扩此。
 * 与 {@link ConditionParams#TIMEZONE} 同字面但分属"scene 配置键 / 条件 param 键"两命名空间。
 */
public final class SceneDefaultParams {
    private SceneDefaultParams() {}

    /** 场景默认时区(IANA 名),时间类算子在条件未声明 timezone 时的兜底。 */
    public static final String TIMEZONE = "timezone";
}
