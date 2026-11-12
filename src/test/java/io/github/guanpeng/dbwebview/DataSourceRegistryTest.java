package io.github.guanpeng.dbwebview;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceRegistryTest {
    static class ModernRoutingDataSource extends DynamicRoutingDataSource {
        private final Map<String, DataSource> children = new LinkedHashMap<>();
        public Map<String, DataSource> getDataSources() { return children; }
        void addChild(String name, DataSource source) { children.put(name, source); }
    }

    @Test
    void listsNamedBeansAndDynamicChildren() {
        StaticApplicationContext context = new StaticApplicationContext();
        DriverManagerDataSource separate = new DriverManagerDataSource("jdbc:h2:mem:separate");
        DriverManagerDataSource master = new DriverManagerDataSource("jdbc:h2:mem:master");
        DriverManagerDataSource slave = new DriverManagerDataSource("jdbc:h2:mem:slave");
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource();
        routing.add("master", master);
        routing.add("slave_1", slave);
        context.getBeanFactory().registerSingleton("custom", separate);
        context.getBeanFactory().registerSingleton("dataSource", routing);

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.dynamic.primary", "master")
                .withProperty("spring.datasource.dynamic.datasource.master.url", "jdbc:h2:mem:master")
                .withProperty("spring.datasource.dynamic.datasource.slave_1.url", "jdbc:h2:mem:slave");
        DataSourceRegistry registry = new DataSourceRegistry(context, environment);
        assertEquals(3, registry.list().size());
        assertSame(separate, registry.require("custom"));
        assertSame(master, registry.require("master"));
        assertSame(slave, registry.require("slave_1"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
    }

    @Test
    void recognizesPrimaryNameFromActiveDynamicConfiguration() {
        StaticApplicationContext context = new StaticApplicationContext();
        DriverManagerDataSource primary = new DriverManagerDataSource("jdbc:h2:mem:szqdpt");
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource();
        routing.add("szqdpt", primary);
        context.getBeanFactory().registerSingleton("dataSource", routing);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.dynamic.primary", "szqdpt")
                .withProperty("spring.datasource.dynamic.datasource.szqdpt.driver-class-name", "org.h2.Driver")
                .withProperty("spring.datasource.dynamic.datasource.szqdpt.username", "sa")
                .withProperty("spring.datasource.dynamic.datasource.szqdpt.password", "")
                .withProperty("spring.datasource.dynamic.datasource.szqdpt.url", "jdbc:h2:mem:szqdpt");

        DataSourceRegistry registry = new DataSourceRegistry(context, environment);
        assertEquals(1, registry.list().size());
        assertSame(primary, registry.require("szqdpt"));
    }

    @Test
    void alsoReadsVersionsThatExposeDataSourcesMap() {
        StaticApplicationContext context = new StaticApplicationContext();
        ModernRoutingDataSource routing = new ModernRoutingDataSource();
        DriverManagerDataSource primary = new DriverManagerDataSource("jdbc:h2:mem:modern");
        routing.addChild("modern", primary);
        context.getBeanFactory().registerSingleton("dataSource", routing);
        DataSourceRegistry registry = new DataSourceRegistry(context, new MockEnvironment());
        assertSame(primary, registry.require("modern"));
    }
}
