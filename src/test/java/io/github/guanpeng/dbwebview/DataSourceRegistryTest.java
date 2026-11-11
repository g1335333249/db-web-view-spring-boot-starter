package io.github.guanpeng.dbwebview;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceRegistryTest {
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

        DataSourceRegistry registry = new DataSourceRegistry(context);
        assertEquals(3, registry.list().size());
        assertSame(separate, registry.require("custom"));
        assertSame(master, registry.require("master"));
        assertSame(slave, registry.require("slave_1"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
    }
}
