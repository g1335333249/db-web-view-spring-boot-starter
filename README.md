# DB Web View Spring Boot Starter

把数据库管理页面嵌入 Spring Boot 2.3.7.RELEASE 应用。支持 MySQL 和 PostgreSQL 的数据源切换、表/视图浏览、服务端筛选排序、分页、表结构查看、CSV 导出、表数据增删改和 SQL 控制台。普通 `spring.datasource.*`、多个自定义 `DataSource` Bean 和 `spring.datasource.dynamic.datasource.*` 均使用应用已经创建的连接池。

## 引入

Java 8+，Servlet Web 应用。执行 `mvn install` 后，在应用中引入：

```xml
<dependency>
    <groupId>io.github.g1335333249</groupId>
    <artifactId>db-web-view-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

在 `application.yml`、当前 profile 的配置文件或环境变量中设置：

```yaml
db-web-view:
  username: ${DB_WEB_VIEW_USER}
  password: ${DB_WEB_VIEW_PASSWORD}
  enabled: true
  read-only: false
  sql-enabled: true
  # 可选：只显示这些数据源；空列表表示全部
  allowed-sources: []
  # 可选：完整的 命名空间.表名；非空时自动关闭 SQL 控制台
  allowed-tables: []
  export-max-rows: 10000
  history-size: 100
  save-sql-text: false
```

访问 `http://localhost:8080/db-web-view/`。只有账号和密码都配置时页面才可用。`enabled: false` 会关闭整个 starter 的自动配置。`application-dev.yml`、`application-prod.yml` 与环境变量按 Spring Boot 的配置优先级生效，页面读取最终创建的数据源 Bean。数据库 JDBC 驱动由宿主应用提供。

多数据源应用可以继续使用原有的 `dynamic-datasource-spring-boot-starter`：

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

页面的数据源列表会出现 `master` 和 `report`。推荐使用 HTTPS、受控网络与最小权限数据库账号。若宿主应用启用了 Spring Security，需允许 `/db-web-view/**` 进入本 starter，并避免宿主 CSRF 规则拦截这些路径；本 starter 对 API 自行执行会话认证与 CSRF 校验。

## 功能与限制

- 数据表支持单字段精确匹配筛选、字段升降序排序和分页。默认每页 50 条；`page-size`、`max-page-size` 可调，上限 1000。
- 表结构页显示字段、主键、索引和外键。CSV 导出使用当前筛选与排序，最多导出 `export-max-rows` 条，硬上限 100000 条；导出值会处理常见的表格公式前缀。
- 视图只读；无主键表可以浏览和新增，但不能精确定位一行修改或删除。二进制/大对象字段显示占位文本，不能在页面编辑。
- `read-only: true` 禁止页面增删改，SQL 窗口只接受以 `SELECT` 开头的单条语句并请求 JDBC 只读连接。**数据库账号仍需设置只读权限**，因为数据库函数等行为不能仅靠 SQL 文本判断。配置 `allowed-tables` 时，SQL 窗口自动关闭，以免绕过表白名单。
- SQL 窗口一次执行一条语句。查询默认最多显示 200 行，超时 30 秒；`sql-max-rows` 和 `sql-timeout-seconds` 可调，硬上限 1000 行和 300 秒。
- SQL 执行历史保存在当前进程内存中，重启即清空，按 `history-size` 限制条数。默认不保存 SQL 原文；只有明确设置 `save-sql-text: true` 才保存。记录时间、数据源、客户端 IP、耗时、结果或错误；日志也记录摘要，不记录 SQL 原文。
- 标识符基于 JDBC 元数据校验并引用，表格筛选及写入值使用预编译参数。SQL 控制台执行的是管理员输入的原始 SQL，应按数据库账号权限控制其能力。

## 测试

`mvn test` 运行 H2 接口测试和真实 `dynamic-datasource` 3.5.2 的数据源发现测试。`ExternalDatabaseIntegrationTest` 可连接实际 MySQL 和 PostgreSQL：设置 `DBWV_MYSQL_URL`、`DBWV_MYSQL_USER`、`DBWV_MYSQL_PASSWORD`，以及 `DBWV_POSTGRES_URL`、`DBWV_POSTGRES_USER`、`DBWV_POSTGRES_PASSWORD` 后运行 `mvn test`。测试会在目标库创建并清空 `dbwv_integration` 表，请使用专用测试库。未设置 URL 时这两项测试跳过。
