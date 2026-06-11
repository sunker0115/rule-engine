package com.baomidou.mybatisplus.aot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.io.IOException;

/**
 * mybatis aot 运行时提示注册器
 *
 * @author xiaochen
 * @since 2026/1/12
 */
class MyBaitsRuntimeHintsRegistrar implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        AotUtils aotUtils = new AotUtils(hints, classLoader);
        registerXml(aotUtils);
    }

    private void registerXml(AotUtils aotUtils) {
        try {
            aotUtils.registerPattern(aotUtils.findResources("",
                name -> name.endsWith(".xml")).toArray(AotUtils.EMPTY_STRING_ARRAY));
        } catch (IOException e) {
            throw new RuntimeException("注册用户资源目录的xml文件失败", e);
        }
    }

}
