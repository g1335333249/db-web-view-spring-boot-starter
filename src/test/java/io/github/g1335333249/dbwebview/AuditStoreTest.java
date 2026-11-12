package io.github.g1335333249.dbwebview;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditStoreTest {
    @Test void auditFailuresAndRevocationAreSharedAcrossInstances() throws Exception {
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:shared_audit;DB_CLOSE_DELAY=-1", "sa", "");
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("audit", db);
        DbWebViewProperties properties = new DbWebViewProperties();
        properties.setAuditSource("audit");
        properties.setAuditAutoCreate(true);
        properties.setAllowedSources(java.util.Collections.singletonList("other"));
        properties.setLoginMaxAttempts(2);
        DataSourceRegistry registry = new DataSourceRegistry(context, new MockEnvironment(), properties);
        AuditStore podA = new AuditStore(registry, properties);
        AuditStore podB = new AuditStore(registry, properties);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("time", Instant.now().toString()); event.put("action", "sql");
        event.put("source", "business"); event.put("kind", "resultSet"); event.put("rowCount", 3);
        podA.record(event);
        assertEquals(1, podB.sqlHistory().size());
        assertEquals("business", podB.sqlHistory().get(0).get("source"));
        podA.failedLogin("192.0.2.10");
        assertFalse(podB.tooManyFailures("192.0.2.10"));
        podB.failedLogin("192.0.2.10");
        assertTrue(podA.tooManyFailures("192.0.2.10"));
        podA.successfulLogin("192.0.2.10");
        assertFalse(podB.tooManyFailures("192.0.2.10"));
        podA.revoke("example-jti", Instant.now().plusSeconds(3600).getEpochSecond());
        assertTrue(podB.revoked("example-jti"));
        String executionId = java.util.UUID.randomUUID().toString();
        podA.beginSqlExecution(executionId);
        assertFalse(podA.sqlCancellationRequested(executionId));
        assertTrue(podB.requestSqlCancellation(executionId));
        assertTrue(podA.sqlCancellationRequested(executionId));
        podA.finishSqlExecution(executionId);
        assertFalse(podB.requestSqlCancellation(executionId));
    }
}
