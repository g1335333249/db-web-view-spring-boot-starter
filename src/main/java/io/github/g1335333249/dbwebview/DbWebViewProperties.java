package io.github.g1335333249.dbwebview;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "db-web-view")
public class DbWebViewProperties {
    private boolean enabled = true;
    private boolean readOnly = false;
    private boolean sqlEnabled = true;
    private java.util.List<String> allowedSources = new java.util.ArrayList<>();
    private java.util.List<String> allowedTables = new java.util.ArrayList<>();
    private int exportMaxRows = 10000;
    private int historySize = 100;
    private boolean saveSqlText = false;
    private String jwtSecret;
    private int jwtTtlSeconds = 12 * 3600;
    private String username;
    private String password;
    private int pageSize = 50;
    private int maxPageSize = 200;
    private int sqlMaxRows = 200;
    private int sqlTimeoutSeconds = 30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean isSqlEnabled() { return sqlEnabled; }
    public void setSqlEnabled(boolean sqlEnabled) { this.sqlEnabled = sqlEnabled; }
    public java.util.List<String> getAllowedSources() { return allowedSources; }
    public void setAllowedSources(java.util.List<String> value) { this.allowedSources = value; }
    public java.util.List<String> getAllowedTables() { return allowedTables; }
    public void setAllowedTables(java.util.List<String> value) { this.allowedTables = value; }
    public int getExportMaxRows() { return exportMaxRows; }
    public void setExportMaxRows(int value) { this.exportMaxRows = value; }
    public int getHistorySize() { return historySize; }
    public void setHistorySize(int value) { this.historySize = value; }
    public boolean isSaveSqlText() { return saveSqlText; }
    public void setSaveSqlText(boolean value) { this.saveSqlText = value; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public int getJwtTtlSeconds() { return jwtTtlSeconds; }
    public void setJwtTtlSeconds(int jwtTtlSeconds) { this.jwtTtlSeconds = jwtTtlSeconds; }
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
