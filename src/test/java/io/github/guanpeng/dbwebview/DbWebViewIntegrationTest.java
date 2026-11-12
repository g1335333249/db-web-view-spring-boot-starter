package io.github.guanpeng.dbwebview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DbWebViewIntegrationTest.App.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dbwv;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "db-web-view.username=admin",
        "db-web-view.password=secret"
})
@AutoConfigureMockMvc
class DbWebViewIntegrationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App { }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(100))");
        jdbc.execute("DELETE FROM ITEMS");
        jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", 1, "first");
    }

    @Test
    void loginCsrfAndCrud() throws Exception {
        mvc.perform(get("/db-web-view")).andExpect(status().isFound());
        mvc.perform(get("/db-web-view/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DB Web View")));
        mvc.perform(get("/db-web-view/app.js")).andExpect(status().isOk());
        mvc.perform(get("/db-web-view/api/sources")).andExpect(status().isUnauthorized());
        mvc.perform(post("/db-web-view/api/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult login = mvc.perform(post("/db-web-view/api/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String csrf = mapper.readTree(login.getResponse().getContentAsString()).get("csrf").asText();
        JsonNode sources = mapper.readTree(mvc.perform(get("/db-web-view/api/sources").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String source = sources.get("sources").get(0).asText();
        JsonNode tables = mapper.readTree(mvc.perform(get("/db-web-view/api/tables").session(session).param("source", source))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(tables.get("tables").toString().contains("ITEMS"));

        String target = "{\"source\":\"" + source + "\",\"namespace\":\"PUBLIC\",\"table\":\"ITEMS\"";
        JsonNode page = mapper.readTree(mvc.perform(get("/db-web-view/api/rows").session(session)
                .param("source", source).param("namespace", "PUBLIC").param("table", "ITEMS"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("first", page.get("rows").get(0).get("NAME").asText());
        assertEquals("ID", page.get("primaryKeys").get(0).asText());
        String sqlBody = "{\"source\":\"" + source + "\",\"sql\":\"SELECT NAME FROM ITEMS WHERE ID = 1;\"}";
        mvc.perform(post("/db-web-view/api/sql").session(session).contentType("application/json")
                .content(sqlBody)).andExpect(status().isForbidden());
        JsonNode sqlResult = mapper.readTree(mvc.perform(post("/db-web-view/api/sql").session(session)
                .header("X-DB-Web-View-CSRF", csrf).contentType("application/json").content(sqlBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("resultSet", sqlResult.get("kind").asText());
        assertEquals("first", sqlResult.get("rows").get(0).get(0).asText());
        mvc.perform(post("/db-web-view/api/sql").session(session).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content("{\"source\":\"" + source
                        + "\",\"sql\":\"SELECT 1; DELETE FROM ITEMS\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/db-web-view/api/rows").session(session).contentType("application/json")
                .content(target + ",\"values\":{\"ID\":2,\"NAME\":\"second\"}}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/db-web-view/api/rows").session(session).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content(target + ",\"values\":{\"ID\":2,\"NAME\":\"second\"}}"))
                .andExpect(status().isOk());
        assertEquals("second", jdbc.queryForObject("SELECT NAME FROM ITEMS WHERE ID=2", String.class));

        mvc.perform(put("/db-web-view/api/rows").session(session).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content(target + ",\"keys\":{\"ID\":2},\"values\":{\"NAME\":\"updated\"}}"))
                .andExpect(status().isOk());
        assertEquals("updated", jdbc.queryForObject("SELECT NAME FROM ITEMS WHERE ID=2", String.class));

        JsonNode updateSqlResult = mapper.readTree(mvc.perform(post("/db-web-view/api/sql").session(session)
                .header("X-DB-Web-View-CSRF", csrf).contentType("application/json")
                .content("{\"source\":\"" + source + "\",\"sql\":\"UPDATE ITEMS SET NAME = 'via SQL' WHERE ID = 2\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("update", updateSqlResult.get("kind").asText());
        assertEquals(1, updateSqlResult.get("affected").asInt());
        assertEquals("via SQL", jdbc.queryForObject("SELECT NAME FROM ITEMS WHERE ID=2", String.class));

        mvc.perform(delete("/db-web-view/api/rows").session(session).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content(target + ",\"keys\":{\"ID\":2}}"))
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ITEMS WHERE ID=2", Integer.class));
    }
}
