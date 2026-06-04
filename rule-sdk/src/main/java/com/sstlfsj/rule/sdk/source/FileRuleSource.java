package com.sstlfsj.rule.sdk.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** JSON 文件模式：从 classpath 加载规则快照，适合离线 / 测试场景。 */
public class FileRuleSource implements RuleSource {

    private final InputStream input;

    private FileRuleSource(InputStream input) {
        this.input = input;
    }

    /**
     * 从 classpath 加载规则 JSON 文件。
     *
     * @param path classpath 相对路径，如 "rules/fraud.json"
     */
    public static FileRuleSource classpath(String path) {
        InputStream in = FileRuleSource.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IllegalArgumentException("classpath 资源不存在：" + path);
        return new FileRuleSource(in);
    }

    @Override
    public void loadInto(SceneRuleIndex index) {
        // 使用 AstJsonCodec 保证 AstNode 多态反序列化正确（与 SnapshotPoller 一致）
        ObjectMapper mapper = new AstJsonCodec().createMapper();
        List<RuleVersionSnapshot> snapshots;
        try {
            snapshots = mapper.readValue(input, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("规则文件解析失败", e);
        }
        new DslRuleSource(snapshots).loadInto(index);
    }
}
