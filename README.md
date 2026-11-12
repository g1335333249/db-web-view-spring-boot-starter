# DB Web View Spring Boot Starter

把数据库管理页面嵌入 Spring Boot 2.3.7.RELEASE 应用。支持 MySQL 和 PostgreSQL 的数据源切换、表/视图浏览、多条件筛选排序、分页、表结构查看、CSV 导出、表数据增删改和 SQL 控制台。普通 `spring.datasource.*`、多个自定义 `DataSource` Bean 和 `spring.datasource.dynamic.datasource.*` 均使用应用已经创建的连接池。

## 接入检查清单

1. 宿主项目使用 Java 8+、Spring Boot 2.3.7.RELEASE、Servlet Web 和 JDBC；提供 MySQL Connector/J 或 PostgreSQL JDBC 驱动，以及至少一个已经能连通数据库的 `DataSource`。MyBatis-Plus 可以继续使用自己的配置，无需为本 starter 单独创建业务数据源。
2. 在本仓库运行 `mvn install`，将下方 Maven 坐标加入宿主项目。当前版本是 `0.1.0-SNAPSHOT`，尚未发布到 Maven Central；其他机器/CI 需从源码安装或使用你自己的 Maven 仓库分发。
3. 设置 `db-web-view.username`、`password` 和至少 32 字节的 `jwt-secret`。使用环境变量或 Kubernetes Secret 注入真实值；`application.yml`、`application-dev.yml`、`application-prod.yml` 均可。确认目标 profile 已激活。
4. 启动宿主应用，访问 `/db-web-view/` 并登录。普通浏览、CRUD、SQL 执行**不需要创建任何本 starter 专用数据表**；它读取应用现有数据表。需要修改/删除时，目标表必须有主键，数据库账号必须具备对应权限。
5. 多 Pod 或需要持久化审计、跨 Pod 取消 SQL 时，按“共享审计数据源”一节另外准备一个所有 Pod 共用的 MySQL/PostgreSQL 数据源，并创建 **4 张** `dbwv_*` 表。这是唯一的 starter 建表步骤；已有部署从 3 张表升级时只需新增 `dbwv_sql_execution`。
6. 若宿主启用了 Spring Security，放行 `/db-web-view/**` 并排除该路径的宿主 CSRF 校验；本 starter 自己验证 JWT 和 CSRF。生产环境通过 HTTPS 访问，代理正确传递协议头。

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
  jwt-secret: ${DB_WEB_VIEW_JWT_SECRET}
  jwt-ttl-seconds: 43200
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
  login-max-attempts: 5
  login-window-seconds: 300
  login-trust-forwarded-for: false
  # 多 Pod 时配置共享审计数据源，名称取自应用已经创建的 DataSource
  # audit-source: audit
  audit-auto-create: false
  audit-retention-days: 30
```

访问 `http://localhost:8080/db-web-view/`。账号、密码和 JWT 密钥都需要配置；`jwt-secret` 至少 32 字节。`enabled: false` 会关闭整个 starter 的自动配置。`application-dev.yml`、`application-prod.yml` 与环境变量按 Spring Boot 的配置优先级生效，页面读取最终创建的数据源 Bean。数据库 JDBC 驱动由宿主应用提供。

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

页面的数据源列表会出现 `master` 和 `report`。推荐使用 HTTPS、受控网络与最小权限数据库账号。反向代理需传递 `X-Forwarded-Proto: https`，以便认证 Cookie 带上 `Secure` 属性。若宿主应用启用了 Spring Security，需允许 `/db-web-view/**` 进入本 starter，并避免宿主 CSRF 规则拦截这些路径；本 starter 对 API 自行执行会话认证与 CSRF 校验。

单数据源项目继续使用普通的 `spring.datasource.url`、`username`、`password` 即可。MyBatis-Plus 的 `mybatis-plus.*` 配置无需改动。只要 Spring 容器中已经注册了可连接的 `DataSource`，本 starter 就会从实际连接池发现数据库；它不会解析未激活 profile 的 YAML 配置。

## Kubernetes 多 Pod 登录

认证使用 HS256 签名的 JWT，存放在 `HttpOnly; SameSite=Lax` Cookie 中；页面刷新后会从 Cookie 恢复登录，不使用 `HttpSession`。所有 Pod 必须从同一个 Kubernetes Secret 获取**相同**的 `DB_WEB_VIEW_JWT_SECRET`，账号与 `jwt-ttl-seconds` 也应一致。可以用 `openssl rand -base64 48` 生成密钥。密钥轮换会使旧令牌失效，需要重新登录。

令牌默认有效 12 小时，在剩余时间少于一半时由活跃请求续期，因此不会因原来的 30 分钟 Session 空闲超时而退出。长时间完全无操作、密钥更换或浏览器删除 Cookie 后仍需重新登录。

登录失败按客户端 IP 计数，默认 5 分钟内失败 5 次后返回 HTTP 429，窗口结束后自动恢复。配置 `audit-source` 时计数和退出登录的 JWT 撤销记录由共享数据库保存，所有 Pod 即时生效；未配置时限流只在当前 Pod 生效，退出登录只清除浏览器 Cookie。如果所有请求都经过可信反向代理，可在代理清理客户端传入的 `X-Forwarded-For` 后设置 `login-trust-forwarded-for: true`，按真实客户端 IP 限流；默认使用连接的 `RemoteAddr`。Kubernetes 入口处仍建议配置请求速率限制。

已登录时，关闭标签页、关闭浏览器、使用关闭快捷键、刷新或离开页面会触发浏览器原生确认提示。浏览器要求页面发生过用户交互才显示提示，提示文案由浏览器决定；部分移动端场景可能不触发。

## 共享审计数据源

生产环境多 Pod 建议配置一个各 Pod 都能访问的专用 MySQL 或 PostgreSQL `DataSource`，例如名为 `audit` 的 dynamic-datasource 子数据源，并设置 `db-web-view.audit-source: audit`。这项配置读取应用现有连接池；即使 `allowed-sources` 不包含 `audit`，内部审计仍可使用它，页面不会列出该数据源。表结构见 [audit-schema.sql](src/main/resources/dbweb/audit-schema.sql)，先在审计库执行，或用 Flyway/Liquibase 管理该 SQL，再给运行账号授予这 4 张表的 SELECT、INSERT、UPDATE、DELETE 权限。`UPDATE` 用于写入 SQL 取消标记。开发环境可设置 `audit-auto-create: true` 自动建表；生产环境默认不自动建表。自动建表不会创建索引，生产环境应执行完整的建表脚本。

例如在现有 dynamic-datasource 配置中增加一个独立连接池：

```yaml
spring:
  datasource:
    dynamic:
      datasource:
        audit:
          driver-class-name: org.postgresql.Driver
          url: jdbc:postgresql://audit-db:5432/dbwv_audit
          username: dbwv_audit
          password: ${DBWV_AUDIT_PASSWORD}
db-web-view:
  audit-source: audit
  allowed-sources: [master, report]
```

这里的 `audit` 名称必须与真实数据源名称一致。先在 `dbwv_audit` 数据库执行完整的 [audit-schema.sql](src/main/resources/dbweb/audit-schema.sql)；从旧版本升级、已有前三张表时，单独执行文件末尾的 `CREATE TABLE IF NOT EXISTS dbwv_sql_execution` 即可。非 dynamic-datasource 项目也可注册一个名为 `audit` 的 `DataSource` Bean。若使用只读或账号权限受限的审计库，必须额外授予上述四项 DML 权限；`audit-auto-create: true` 还需要 CREATE TABLE 权限。

| 表 | 用途 |
| --- | --- |
| `dbwv_audit_event` | SQL 和表格写操作记录 |
| `dbwv_token_revocation` | 跨 Pod 退出登录后撤销 JWT |
| `dbwv_login_failure` | 跨 Pod 登录失败计数 |
| `dbwv_sql_execution` | 跨 Pod SQL 取消请求，执行结束后删除 |

审计库建议使用独立连接池，避免被运行中的业务查询占满。所有 Pod 需指向同一审计库。若不配置 `audit-source`，SQL 取消仍可取消当前 Pod 的执行，但请求被负载均衡到另一 Pod 时无法定位；数据库自身是否响应 `Statement.cancel()` 取决于驱动和数据库状态，取消不是事务回滚保证。

操作审计页可查看最近的 SQL 和表格增删改记录。默认保留 30 天，每个 Pod 最多每小时清理一次过期审计记录、登录失败记录和撤销记录；`history-size` 控制页面显示条数。默认不保存 SQL 原文，开启 `save-sql-text` 后最多保存前 15000 个字符。未配置 `audit-source` 时记录仍保存在当前 Pod 的内存中，重启会丢失。数据库写操作前会检查审计数据源；若操作完成后审计写入失败，接口会返回操作结果和 `auditWarning`，避免用户误以为写入失败而重复操作。

## 功能与限制

- 数据表最多可添加 20 个筛选条件，按 AND 组合，支持等于、不等于、包含、大于/小于、闭区间范围、为空/不为空。“包含”只适用于文本列，`%`、`_` 按普通字符匹配。筛选同样作用于表格 CSV 导出；旧版 JSON 对象等值筛选参数仍兼容。支持字段升降序排序和分页。默认的单列主键升序浏览使用游标翻页；自定义排序和无单列主键的表仍用 OFFSET。默认每页 50 条；`page-size`、`max-page-size` 可调，上限 1000。
- 表结构页显示字段、主键、索引和外键。CSV 导出使用当前筛选与排序，边查边写，不在服务端或浏览器一次性组装完整文件，最多导出 `export-max-rows` 条，硬上限 100000 条；导出值会处理常见的表格公式前缀。
- 视图只读；无主键表可以浏览和新增，但不能精确定位一行修改或删除。二进制/大对象字段显示占位文本，不能在页面编辑。
- `read-only: true` 禁止页面增删改，SQL 窗口只接受以 `SELECT` 开头的单条语句并请求 JDBC 只读连接。**数据库账号仍需设置只读权限**，因为数据库函数等行为不能仅靠 SQL 文本判断。配置 `allowed-tables` 时，SQL 窗口自动关闭，以免绕过表白名单。
- SQL 窗口一次执行一条语句。查询默认最多显示 200 行，超时 30 秒；`sql-max-rows` 和 `sql-timeout-seconds` 可调，硬上限 1000 行和 300 秒。“执行计划”只接受单条 `SELECT`，执行 `EXPLAIN`，不使用 `ANALYZE`。“导出当前结果 CSV”导出页面已返回的行，因此同样受 `sql-max-rows` 限制；如需大批量导出，请使用表格 CSV 导出。运行中的 SQL 可以点“取消执行”，服务端调用 JDBC `Statement.cancel()`；共享审计库启用时其他 Pod 可传递取消请求，轮询间隔约 500 毫秒。
- SQL 执行历史记录时间、数据源、客户端 IP、耗时、结果或错误；日志也记录摘要。配置共享审计数据源后可跨 Pod 查看，未配置时只在当前 Pod 内存中。默认不保存 SQL 原文；只有明确设置 `save-sql-text: true` 才保存。
- 标识符基于 JDBC 元数据校验并引用，表格筛选及写入值使用预编译参数。SQL 控制台执行的是管理员输入的原始 SQL，应按数据库账号权限控制其能力。

## 测试

`mvn test` 运行 H2 接口测试和真实 `dynamic-datasource` 3.5.2 的数据源发现测试。`ExternalDatabaseIntegrationTest` 可连接实际 MySQL 和 PostgreSQL：设置 `DBWV_MYSQL_URL`、`DBWV_MYSQL_USER`、`DBWV_MYSQL_PASSWORD`，以及 `DBWV_POSTGRES_URL`、`DBWV_POSTGRES_USER`、`DBWV_POSTGRES_PASSWORD` 后运行 `mvn test`。测试会在目标库创建并清空 `dbwv_integration` 表，请使用专用测试库。未设置 URL 时这两项测试跳过。
