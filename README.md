# DB Web View Spring Boot Starter

将数据库管理页面嵌入 Spring Boot 2.3.7.RELEASE 应用。支持 MySQL 和 PostgreSQL 的数据源切换、表/视图浏览、分页查询、表数据增删改以及 SQL 控制台。普通 `spring.datasource.*`、多个自定义 `DataSource` Bean，以及 `spring.datasource.dynamic.datasource.*`（dynamic-datasource）都使用应用已经创建的连接池，不会读取配置文件中的原始密码或另建连接池。

## 引入

Java 8+，Servlet Web 应用。将本项目 `mvn install` 后，在应用中引入：

```xml
<dependency>
    <groupId>io.github.guanpeng</groupId>
    <artifactId>db-web-view-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

在 `application.yml`、当前 profile 的配置文件或环境变量中设置：

```yaml
db-web-view:
  username: ${DB_WEB_VIEW_USER}
  password: ${DB_WEB_VIEW_PASSWORD}
```

启动后访问 `http://localhost:8080/db-web-view/`。只有账号和密码都配置时页面才可用。`spring.datasource.*` 与 `spring.datasource.dynamic.datasource.*` 保持原应用的配置格式，无需在本 starter 中重复配置。页面可按数据源名称切换；`dynamic-datasource` 的具体子数据源会作为独立选项出现。

数据库 JDBC 驱动由宿主应用提供。例如 MySQL 需要 `mysql-connector-java`，PostgreSQL 需要 `postgresql`。`application-dev.yml`、`application-prod.yml` 和环境变量会按 Spring Boot 自身的优先级生效；页面读取的是最终创建的数据源 Bean。

多数据源应用继续使用原有的 `dynamic-datasource-spring-boot-starter`，例如：

```yaml
spring:
  datasource:
    dynamic:
      primary: master
      datasource:
        master:
          url: jdbc:mysql://localhost:3306/app
          username: app
          password: ${APP_DB_PASSWORD}
        report:
          url: jdbc:postgresql://localhost:5432/report
          username: report
          password: ${REPORT_DB_PASSWORD}
```

登录后，页面的数据源列表会出现 `master` 和 `report`。配置在 `application-dev.yml` 或 `application-prod.yml` 中时，只有当前激活 profile 创建的数据源会出现。

如宿主应用启用了 Spring Security，需在宿主应用安全规则中允许 `/db-web-view/**` 进入本 starter，并避免宿主 CSRF 规则拦截这些路径；本 starter 对 API 独立执行会话认证和 CSRF 校验。建议仅在受控网络或经 HTTPS 的反向代理下开放管理入口，并使用最小权限的数据库账号。

## 行为和限制

- 默认每页 50 条，`db-web-view.page-size`、`db-web-view.max-page-size` 可调整（上限 1000）。
- 视图只读；无主键表可浏览和新增，但不能精确定位一行来修改或删除。
- 二进制/大对象字段只显示占位文本，不能在页面编辑。
- SQL 控制台每次执行一条语句，允许查询、写入和结构变更。查询最多显示 200 行，执行超时 30 秒；分别可用 `db-web-view.sql-max-rows` 和 `db-web-view.sql-timeout-seconds` 调整，硬上限为 1000 行和 300 秒。SQL 不保存到浏览器或服务器历史中，审计日志只记录语句结果和数据源，不记录 SQL 内容。
- 使用 Java 标准 JDBC 元数据定位表和字段，写操作只使用经过校验的标识符与预编译参数。
