package com.sstlfsj.rule.sdk.source;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;

import java.io.InputStream;
import java.util.List;

/** JSON 文件模式定义来源：从 classpath 加载某租户的 {@link MetricDescriptor} 列表，适合离线/测试场景。 */
public class FileMetricDefinitionSource implements MetricDefinitionSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String tenantId;
    private final InputStream input;

    private FileMetricDefinitionSource(String tenantId, InputStream input) {
        this.tenantId = tenantId;
        this.input = input;
    }

    /**
     * 从 classpath 加载定义 JSON 文件。
     *
     * @param tenantId 定义所属租户 id
     * @param path     classpath 相对路径，如 "metric-definitions/fraud.json"
     * @return 定义来源实例
     */
    public static FileMetricDefinitionSource classpath(String tenantId, String path) {
        InputStream in = FileMetricDefinitionSource.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IllegalArgumentException("classpath 资源不存在：" + path);
        return new FileMetricDefinitionSource(tenantId, in);
    }

    @Override
    public void loadInto(MetricDefinitionRegistry registry) {
        List<MetricDescriptor> list = MAPPER.readValue(input, new TypeReference<List<MetricDescriptor>>() {});
        for (MetricDescriptor d : list) {
            registry.put(tenantId, d);
        }
    }
}
