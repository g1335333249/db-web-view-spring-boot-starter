package io.github.guanpeng.dbwebview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(DbWebViewProperties.class)
public class DbWebViewAutoConfiguration {
    @Bean
    public DataSourceRegistry dbWebViewDataSourceRegistry(ApplicationContext context) {
        return new DataSourceRegistry(context);
    }

    @Bean
    public DatabaseService dbWebViewDatabaseService(DataSourceRegistry registry, DbWebViewProperties properties) {
        return new DatabaseService(registry, properties);
    }

    @Bean
    public DbWebViewController dbWebViewController(DatabaseService service, DbWebViewProperties properties) {
        return new DbWebViewController(service, properties);
    }

    @Bean
    public DbWebViewAuthFilter dbWebViewAuthFilter(DbWebViewProperties properties) {
        return new DbWebViewAuthFilter(properties);
    }
}
