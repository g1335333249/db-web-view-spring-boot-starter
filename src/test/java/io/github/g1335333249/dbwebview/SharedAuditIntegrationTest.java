package io.github.g1335333249.dbwebview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SharedAuditIntegrationTest.App.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:shared_audit_api;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.autoconfigure.exclude=com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration",
        "db-web-view.username=admin", "db-web-view.password=secret",
        "db-web-view.jwt-secret=abcdefghijklmnopqrstuvwxyz0123456789-SharedSecret",
        "db-web-view.audit-source=dataSource", "db-web-view.audit-auto-create=true"
})
@AutoConfigureMockMvc
class SharedAuditIntegrationTest {
    @SpringBootConfiguration @EnableAutoConfiguration static class App { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void auditAndLogoutRevocationUseSharedDatabase() throws Exception {
        MvcResult login = mvc.perform(post("/db-web-view/api/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/db-web-view/api/sql").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"source\":\"dataSource\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk());
        MvcResult audit = mvc.perform(get("/db-web-view/api/audit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertTrue(audit.getResponse().getContentAsString().contains("resultSet"));
        mvc.perform(post("/db-web-view/api/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/db-web-view/api/sources").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
}
