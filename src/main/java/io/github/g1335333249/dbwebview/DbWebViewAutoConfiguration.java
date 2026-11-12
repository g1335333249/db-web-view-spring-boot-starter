package io.github.g1335333249.dbwebview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "db-web-view", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DbWebViewProperties.class)
public class DbWebViewAutoConfiguration {
    @Bean
    public DataSourceRegistry dbWebViewDataSourceRegistry(ApplicationContext context, Environment environment, DbWebViewProperties properties) {
        return new DataSourceRegistry(context, environment, properties);
    }

    @Bean
    public DatabaseService dbWebViewDatabaseService(DataSourceRegistry registry, DbWebViewProperties properties) {
        return new DatabaseService(registry, properties);
    }

    @Bean
    public SqlConsoleService dbWebViewSqlConsoleService(DataSourceRegistry registry, DbWebViewProperties properties) {
        return new SqlConsoleService(registry, properties);
    }

    @Bean
    public DbWebViewController dbWebViewController(DatabaseService service, SqlConsoleService sqlService,
                                                   DbWebViewProperties properties) {
        return new DbWebViewController(service, sqlService, properties);
    }

    @Bean
    public DbWebViewAuthFilter dbWebViewAuthFilter(DbWebViewProperties properties) {
        return new DbWebViewAuthFilter(properties);
    }
}
