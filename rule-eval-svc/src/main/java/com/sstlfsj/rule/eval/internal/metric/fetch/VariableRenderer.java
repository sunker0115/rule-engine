package com.sstlfsj.rule.eval.internal.metric.fetch;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨源变量命名空间渲染（共用脊·调用无关）。命名空间：payload/params/vars/subject/now/subjectId/tenantId。
 * HTTP 用 {x} 占位（renderTemplate，含 URL 编码）；SQL 用 :x 命名参数（resolve 取值，SQL handler 自绑）。
 */
public class VariableRenderer {

    private static final Pattern PH = Pattern.compile("\\{([a-zA-Z_][\\w.]*)}");

    /**
     * 渲染上下文。
     *
     * @param subjectId         主体 id
     * @param tenantId          租户 id
     * @param now               引擎统一时钟
     * @param payload           事件 payload
     * @param params            metric.params.params 子 map
     * @param vars              metric.params.vars（连接器入参）
     * @param subjectAttributes 主体属性（来自 Subject）
     */
    public record Context(String subjectId, String tenantId, Instant now,
                          Map<String, Object> payload, Map<String, Object> params,
                          Map<String, Object> vars, Map<String, Object> subjectAttributes) {}

    /** 渲染含 {ns.key} 占位的模板，逐段 URL 编码（供 HTTP path/query/header/body）。 */
    public String renderTemplate(String template, Context ctx) {
        Matcher m = PH.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String[] parts = m.group(1).split("\\.", 2);
            Object v = resolve(parts[0], parts.length == 2 ? parts[1] : null, ctx);
            String enc = URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8).replace("+", "%20");
            m.appendReplacement(out, Matcher.quoteReplacement(enc));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 解析单个命名空间引用值；未知命名空间/缺键返回 null。 */
    public Object resolve(String namespace, String key, Context ctx) {
        return switch (namespace) {
            case "payload" -> ctx.payload().get(key);
            case "params" -> ctx.params().get(key);
            case "vars" -> ctx.vars().get(key);
            case "subject" -> ctx.subjectAttributes().get(key);
            case "subjectId" -> ctx.subjectId();
            case "tenantId" -> ctx.tenantId();
            case "now" -> ctx.now();
            default -> null;
        };
    }

    /** 模板是否引用了 subject.* 命名空间（供 assembler 判定是否需等 subject 加载）。 */
    public boolean referencesSubject(String template) {
        Matcher m = PH.matcher(template);
        while (m.find()) {
            if (m.group(1).startsWith("subject.")) return true;
        }
        return false;
    }
}
