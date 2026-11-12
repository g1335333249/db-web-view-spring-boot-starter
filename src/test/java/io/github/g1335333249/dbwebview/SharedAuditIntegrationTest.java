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

import javax.servlet.http.Cookie;

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
        String csrf = mapper.readTree(login.getResponse().getContentAsString()).get("csrf").asText();
        String token = login.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        Cookie cookie = new Cookie("DBWV_AUTH", token);
        mvc.perform(post("/db-web-view/api/sql").cookie(cookie).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json")
                .content("{\"source\":\"dataSource\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk());
        MvcResult audit = mvc.perform(get("/db-web-view/api/audit").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        assertTrue(audit.getResponse().getContentAsString().contains("resultSet"));
        mvc.perform(post("/db-web-view/api/logout").cookie(cookie).header("X-DB-Web-View-CSRF", csrf))
                .andExpect(status().isOk());
        mvc.perform(get("/db-web-view/api/sources").cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
