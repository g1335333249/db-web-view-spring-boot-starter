package io.github.guanpeng.dbwebview;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlConsoleServiceTest {
    @Test
    void capsReturnedRowsAndReportsTruncation() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:sql_limit;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE T (ID INT PRIMARY KEY)");
            statement.execute("INSERT INTO T (ID) VALUES (1), (2)");
        }
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("limited", dataSource);
        DbWebViewProperties properties = new DbWebViewProperties();
        properties.setSqlMaxRows(1);
        SqlConsoleService service = new SqlConsoleService(new DataSourceRegistry(context, new MockEnvironment()), properties);

        Map<String, Object> result = service.execute("limited", "SELECT ID FROM T ORDER BY ID");
        assertEquals("resultSet", result.get("kind"));
        assertEquals(1, result.get("rowCount"));
        assertEquals(true, result.get("truncated"));
    }
}
