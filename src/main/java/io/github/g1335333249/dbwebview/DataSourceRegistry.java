package io.github.g1335333249.dbwebview;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the application's live DataSource beans; it never rebuilds pools from YAML. */
public class DataSourceRegistry {
    private static final String DYNAMIC_CLASS = "com.baomidou.dynamic.datasource.DynamicRoutingDataSource";
    private static final String HOLDER_CLASS = "com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder";
    private final ApplicationContext context;
    private final DbWebViewProperties properties;
    private final Environment environment;

    public DataSourceRegistry(ApplicationContext context, Environment environment, DbWebViewProperties properties) {
        this.context = context;
        this.environment = environment;
        this.properties = properties;
    }

    public Map<String, DataSource> list() {
        Map<String, DataSource> beans = context.getBeansOfType(DataSource.class);
        Map<String, DataSource> result = new LinkedHashMap<>();
        for (Map.Entry<String, DataSource> bean : beans.entrySet()) {
            DataSource source = bean.getValue();
            if (!isDynamic(source)) {
                result.put(bean.getKey(), source);
                continue;
            }
            Map<String, DataSource> children = dynamicChildren(source);
            if (children.isEmpty()) result.put(bean.getKey(), source);
            else result.putAll(children);
        }
        if (!properties.getAllowedSources().isEmpty()) result.keySet().retainAll(properties.getAllowedSources());
        return Collections.unmodifiableMap(result);
    }

    public DataSource require(String name) {
        DataSource dataSource = list().get(name);
        if (dataSource == null) throw new IllegalArgumentException("未知数据源: " + name);
        return dataSource;
    }

    private boolean isDynamic(DataSource source) {
        for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
            if (DYNAMIC_CLASS.equals(type.getName())) return true;
        }
        return false;
    }

    private Map<String, DataSource> dynamicChildren(DataSource source) {
        Map<String, DataSource> direct = readDataSourcesMethod(source);
        if (!direct.isEmpty()) return direct;

        Map<String, DynamicDataSourceDefinition> configured = Binder.get(environment)
                .bind("spring.datasource.dynamic.datasource",
                        Bindable.mapOf(String.class, DynamicDataSourceDefinition.class))
                .orElse(Collections.emptyMap());
        if (configured.isEmpty()) return Collections.emptyMap();

        Map<String, DataSource> result = new LinkedHashMap<>();
        Method getter = publicMethod(source.getClass(), "getDataSource", String.class);
        for (String name : configured.keySet()) {
            DataSource child = getter == null ? null : invokeDataSource(getter, source, name);
            result.put(name, child != null ? child : routed(source, name));
        }
        return result;
    }

    private Map<String, DataSource> readDataSourcesMethod(DataSource source) {
        Method getter = publicMethod(source.getClass(), "getDataSources");
        if (getter == null) return Collections.emptyMap();
        try {
            Object raw = getter.invoke(source);
            if (!(raw instanceof Map)) return Collections.emptyMap();
            Map<String, DataSource> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() instanceof DataSource)
                    result.put((String) entry.getKey(), (DataSource) entry.getValue());
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return Collections.emptyMap();
        }
    }

    private Method publicMethod(Class<?> type, String name, Class<?>... args) {
        try { return type.getMethod(name, args); }
        catch (NoSuchMethodException ex) { return null; }
    }

    private DataSource invokeDataSource(Method getter, DataSource source, String name) {
        try {
            Object value = getter.invoke(source, name);
            return value instanceof DataSource ? (DataSource) value : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private DataSource routed(DataSource source, String name) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return open(null, null); }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                return open(username, password);
            }
            private Connection open(String username, String password) throws SQLException {
                try {
                    Class<?> holder = Class.forName(HOLDER_CLASS, true, source.getClass().getClassLoader());
                    holder.getMethod("push", String.class).invoke(null, name);
                    try {
                        return username == null ? source.getConnection() : source.getConnection(username, password);
                    } finally {
                        holder.getMethod("poll").invoke(null);
                    }
                } catch (ReflectiveOperationException ex) {
                    throw new SQLException("无法切换到动态数据源 " + name, ex);
                }
            }
        };
    }

    /** Only used to discover configured names through Spring Boot's active Environment. */
    public static class DynamicDataSourceDefinition {
        private String url;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
