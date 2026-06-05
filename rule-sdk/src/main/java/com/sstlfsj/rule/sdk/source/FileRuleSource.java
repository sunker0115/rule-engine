package com.sstlfsj.rule.sdk.source;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.io.InputStream;
import java.util.List;

/** JSON 文件模式：从 classpath 加载规则快照，适合离线 / 测试场景。 */
public class FileRuleSource implements RuleSource {

    private static final ObjectMapper MAPPER = new AstJsonCodec().createMapper();

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
        List<RuleVersionSnapshot> snapshots;
        snapshots = MAPPER.readValue(input, new TypeReference<>() {});
        new DslRuleSource(snapshots).loadInto(index);
    }
}
