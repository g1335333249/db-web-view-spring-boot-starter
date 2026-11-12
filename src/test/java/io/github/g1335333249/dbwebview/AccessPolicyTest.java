package io.github.g1335333249.dbwebview;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
    @Test void sourceAndTableAllowListsAndReadOnly() throws Exception {
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:policy;DB_CLOSE_DELAY=-1", "sa", "");
        try (java.sql.Connection connection = db.getConnection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS PUBLIC.ITEMS (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            statement.execute("CREATE TABLE IF NOT EXISTS PUBLIC.HIDDEN (ID INT PRIMARY KEY)");
        }
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("visible", db);
        context.getBeanFactory().registerSingleton("blocked", new DriverManagerDataSource("jdbc:h2:mem:blocked", "sa", ""));
        DbWebViewProperties properties = new DbWebViewProperties();
        properties.setAllowedSources(Collections.singletonList("visible"));
        properties.setAllowedTables(Collections.singletonList("PUBLIC.ITEMS"));
        properties.setReadOnly(true);
        DataSourceRegistry registry = new DataSourceRegistry(context, new MockEnvironment(), properties);
        DatabaseService service = new DatabaseService(registry, properties);
        SqlConsoleService console = new SqlConsoleService(registry, properties);
        assertEquals(Collections.singletonList("visible"), service.sources());
        assertThrows(IllegalArgumentException.class, () -> registry.require("blocked"));
        assertEquals(1, service.tables("visible").size());
        assertThrows(IllegalArgumentException.class, () -> service.rows("visible", "PUBLIC", "HIDDEN", 0, 10));
        assertEquals(Boolean.TRUE, service.rows("visible", "PUBLIC", "ITEMS", 0, 10).get("readOnly"));
        assertThrows(IllegalArgumentException.class, () -> service.insert("visible", "PUBLIC", "ITEMS", Collections.singletonMap("ID", 1)));
        assertEquals("resultSet", console.execute("visible", "SELECT * FROM PUBLIC.ITEMS").get("kind"));
        assertThrows(IllegalArgumentException.class, () -> console.execute("visible", "DELETE FROM PUBLIC.ITEMS"));
    }
}
