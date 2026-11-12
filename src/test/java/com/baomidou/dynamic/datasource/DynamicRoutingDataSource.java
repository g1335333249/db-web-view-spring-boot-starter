package com.baomidou.dynamic.datasource;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal test double for the optional dynamic-datasource API. */
public class DynamicRoutingDataSource extends AbstractDataSource {
    private final Map<String, DataSource> dataSources = new LinkedHashMap<>();

    public void add(String name, DataSource dataSource) { dataSources.put(name, dataSource); }
    public DataSource getDataSource(String name) { return dataSources.get(name); }

    @Override public Connection getConnection() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Connection getConnection(String username, String password) throws SQLException { throw new UnsupportedOperationException(); }
}
