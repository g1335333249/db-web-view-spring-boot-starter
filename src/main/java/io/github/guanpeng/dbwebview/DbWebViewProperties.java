package io.github.guanpeng.dbwebview;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "db-web-view")
public class DbWebViewProperties {
    private String username;
    private String password;
    private int pageSize = 50;
    private int maxPageSize = 200;
    private int sqlMaxRows = 200;
    private int sqlTimeoutSeconds = 30;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
    public int getSqlMaxRows() { return sqlMaxRows; }
    public void setSqlMaxRows(int sqlMaxRows) { this.sqlMaxRows = sqlMaxRows; }
    public int getSqlTimeoutSeconds() { return sqlTimeoutSeconds; }
    public void setSqlTimeoutSeconds(int sqlTimeoutSeconds) { this.sqlTimeoutSeconds = sqlTimeoutSeconds; }
}
