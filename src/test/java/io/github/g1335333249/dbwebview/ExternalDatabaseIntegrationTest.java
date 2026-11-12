package io.github.g1335333249.dbwebview;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Set DBWV_MYSQL_URL/USER/PASSWORD and DBWV_POSTGRES_URL/USER/PASSWORD to run against real servers. */
class ExternalDatabaseIntegrationTest {
    @Test void mysql() throws Exception { exercise("MYSQL", null, "dbwv_integration"); }
    @Test void postgres() throws Exception { exercise("POSTGRES", "public", "dbwv_integration"); }

    private void exercise(String prefix, String namespace, String name) throws Exception {
        String url = System.getenv("DBWV_" + prefix + "_URL");
        assumeTrue(url != null && !url.isEmpty(), "Set DBWV_" + prefix + "_URL to run this integration test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv("DBWV_" + prefix + "_USER"), System.getenv("DBWV_" + prefix + "_PASSWORD"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + name + " (id BIGINT PRIMARY KEY, value_text VARCHAR(100))");
            statement.execute("DELETE FROM " + name);
            statement.execute("INSERT INTO " + name + " (id, value_text) VALUES (1, 'real-db')");
        }
        if (namespace == null) try (Connection connection = dataSource.getConnection()) { namespace = connection.getCatalog(); }
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("real", (DataSource) dataSource);
        DbWebViewProperties properties = new DbWebViewProperties();
        DatabaseService service = new DatabaseService(new DataSourceRegistry(context, new MockEnvironment(), properties), properties);
        assertTrue(service.tables("real").stream().anyMatch(table -> name.equalsIgnoreCase(table.get("name"))));
        assertEquals(1, ((java.util.List<?>) service.rows("real", namespace, name, 0, 20,
                Collections.singletonMap("value_text", "real-db"), "id", true).get("rows")).size());
        java.io.ByteArrayOutputStream csv = new java.io.ByteArrayOutputStream();
        service.writeCsv(csv, "real", namespace, name, Collections.emptyMap(), "id", false);
        assertTrue(csv.toString("UTF-8").contains("real-db"));
        assertFalse((Boolean) service.rows("real", namespace, name, 0, 20).get("readOnly"));
    }
}
