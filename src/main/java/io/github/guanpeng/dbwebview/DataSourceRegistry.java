package io.github.guanpeng.dbwebview;

import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the application's live DataSource beans; it never rebuilds pools from YAML or reads passwords. */
public class DataSourceRegistry {
    private final ApplicationContext context;

    public DataSourceRegistry(ApplicationContext context) {
        this.context = context;
    }

    public Map<String, DataSource> list() {
        Map<String, DataSource> beans = context.getBeansOfType(DataSource.class);
        Map<String, DataSource> result = new LinkedHashMap<>();
        for (Map.Entry<String, DataSource> entry : beans.entrySet()) {
            Map<String, DataSource> nested = dynamicChildren(entry.getValue());
            if (nested.isEmpty()) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                result.putAll(nested);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public DataSource require(String name) {
        DataSource dataSource = list().get(name);
        if (dataSource == null) throw new IllegalArgumentException("未知数据源: " + name);
        return dataSource;
    }

    private Map<String, DataSource> dynamicChildren(DataSource source) {
        Class<?> type = source.getClass();
        while (type != null && !type.getName().equals("com.baomidou.dynamic.datasource.DynamicRoutingDataSource")) {
            type = type.getSuperclass();
        }
        if (type == null) {
            return Collections.emptyMap();
        }
        try {
            Method method = source.getClass().getMethod("getDataSources");
            Object raw = method.invoke(source);
            if (!(raw instanceof Map)) return Collections.emptyMap();
            Map<String, DataSource> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() instanceof DataSource) {
                    result.put((String) entry.getKey(), (DataSource) entry.getValue());
                }
            }
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法读取 dynamic-datasource 的实际数据源", ex);
        }
    }
}
