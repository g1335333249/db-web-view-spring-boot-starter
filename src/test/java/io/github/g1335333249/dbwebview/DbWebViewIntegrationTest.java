package io.github.g1335333249.dbwebview;

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
import javax.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DbWebViewIntegrationTest.App.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dbwv;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.autoconfigure.exclude=com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration",
        "db-web-view.username=admin",
        "db-web-view.password=secret",
        "db-web-view.jwt-secret=abcdefghijklmnopqrstuvwxyz0123456789-SharedSecret"
})
@AutoConfigureMockMvc
class DbWebViewIntegrationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App { }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseService databaseService;
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
        Cookie cookie = cookie(login);
        String csrf = mapper.readTree(login.getResponse().getContentAsString()).get("csrf").asText();
        mvc.perform(get("/db-web-view/api/session").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(csrf)));
        JsonNode sources = mapper.readTree(mvc.perform(get("/db-web-view/api/sources").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String source = sources.get("sources").get(0).asText();
        JsonNode tables = mapper.readTree(mvc.perform(get("/db-web-view/api/tables").cookie(cookie).param("source", source))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(tables.get("tables").toString().contains("ITEMS"));

        String target = "{\"source\":\"" + source + "\",\"namespace\":\"PUBLIC\",\"table\":\"ITEMS\"";
        JsonNode page = mapper.readTree(mvc.perform(get("/db-web-view/api/rows").cookie(cookie)
                .param("source", source).param("namespace", "PUBLIC").param("table", "ITEMS"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("first", page.get("rows").get(0).get("NAME").asText());
        assertEquals("ID", page.get("primaryKeys").get(0).asText());
        String sqlBody = "{\"source\":\"" + source + "\",\"sql\":\"SELECT NAME FROM ITEMS WHERE ID = 1;\"}";
        mvc.perform(post("/db-web-view/api/sql").cookie(cookie).contentType("application/json")
                .content(sqlBody)).andExpect(status().isForbidden());
        JsonNode sqlResult = mapper.readTree(mvc.perform(post("/db-web-view/api/sql").cookie(cookie)
                .header("X-DB-Web-View-CSRF", csrf).contentType("application/json").content(sqlBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("resultSet", sqlResult.get("kind").asText());
        assertEquals("first", sqlResult.get("rows").get(0).get(0).asText());
        mvc.perform(post("/db-web-view/api/sql").cookie(cookie).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content("{\"source\":\"" + source
                        + "\",\"sql\":\"SELECT 1; DELETE FROM ITEMS\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/db-web-view/api/rows").cookie(cookie).contentType("application/json")
                .content(target + ",\"values\":{\"ID\":2,\"NAME\":\"second\"}}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/db-web-view/api/rows").cookie(cookie).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content(target + ",\"values\":{\"ID\":2,\"NAME\":\"second\"}}"))
                .andExpect(status().isOk());
        assertEquals("second", jdbc.queryForObject("SELECT NAME FROM ITEMS WHERE ID=2", String.class));

        mvc.perform(put("/db-web-view/api/rows").cookie(cookie).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content(target + ",\"keys\":{\"ID\":2},\"values\":{\"NAME\":\"updated\"}}"))
                .andExpect(status().isOk());
        assertEquals("updated", jdbc.queryForObject("SELECT NAME FROM ITEMS WHERE ID=2", String.class));

        JsonNode updateSqlResult = mapper.readTree(mvc.perform(post("/db-web-view/api/sql").cookie(cookie)
                .header("X-DB-Web-View-CSRF", csrf).contentType("application/json")
                .content("{\"source\":\"" + source + "\",\"sql\":\"UPDATE ITEMS SET NAME = 'via SQL' WHERE ID = 2\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("update", updateSqlResult.get("kind").asText());
        assertEquals(1, updateSqlResult.get("affected").asInt());
        assertEquals("via SQL", jdbc.queryForObject("SELECT NAME FROM ITEMS WHERE ID=2", String.class));

        mvc.perform(delete("/db-web-view/api/rows").cookie(cookie).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content(target + ",\"keys\":{\"ID\":2}}"))
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ITEMS WHERE ID=2", Integer.class));
        mvc.perform(post("/db-web-view/api/logout").cookie(cookie).header("X-DB-Web-View-CSRF", csrf))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
    @Test
    void filterSortStructureExportAndHistory() throws Exception {
        jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", 2, "second");
        MvcResult login = mvc.perform(post("/db-web-view/api/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = cookie(login);
        String csrf = mapper.readTree(login.getResponse().getContentAsString()).get("csrf").asText();
        String source = mapper.readTree(mvc.perform(get("/db-web-view/api/sources").cookie(cookie))
                .andReturn().getResponse().getContentAsString()).get("sources").get(0).asText();
        JsonNode filtered = mapper.readTree(mvc.perform(get("/db-web-view/api/rows").cookie(cookie)
                .param("source", source).param("namespace", "PUBLIC").param("table", "ITEMS")
                .param("filters", "{\"NAME\":\"second\"}").param("sort", "ID").param("descending", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, filtered.get("rows").size());
        assertEquals(2, filtered.get("rows").get(0).get("ID").asInt());
        mvc.perform(get("/db-web-view/api/rows").cookie(cookie).param("source", source)
                .param("namespace", "PUBLIC").param("table", "ITEMS").param("sort", "MISSING"))
                .andExpect(status().isBadRequest());
        JsonNode structure = mapper.readTree(mvc.perform(get("/db-web-view/api/structure").cookie(cookie)
                .param("source", source).param("namespace", "PUBLIC").param("table", "ITEMS"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("ID", structure.get("primaryKeys").get(0).asText());
        mvc.perform(get("/db-web-view/api/export").cookie(cookie).param("source", source)
                .param("namespace", "PUBLIC").param("table", "ITEMS").param("filters", "{\"NAME\":\"second\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("second")));
        mvc.perform(post("/db-web-view/api/sql").cookie(cookie).header("X-DB-Web-View-CSRF", csrf)
                .contentType("application/json").content("{\"source\":\"" + source + "\",\"sql\":\"SELECT COUNT(*) FROM ITEMS\"}"))
                .andExpect(status().isOk());
        JsonNode history = mapper.readTree(mvc.perform(get("/db-web-view/api/sql/history").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(history.get("history").size() > 0);
        assertFalse(history.get("history").get(0).has("sql"));
    }

    @Test
    void keysetCursorContinuesAfterEarlierRowIsDeleted() throws Exception {
        jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", 2, "two");
        jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", 3, "three");
        MvcResult login = mvc.perform(post("/db-web-view/api/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = cookie(login);
        String source = mapper.readTree(mvc.perform(get("/db-web-view/api/sources").cookie(cookie))
                .andReturn().getResponse().getContentAsString()).get("sources").get(0).asText();
        JsonNode first = mapper.readTree(mvc.perform(get("/db-web-view/api/rows").cookie(cookie)
                .param("source", source).param("namespace", "PUBLIC").param("table", "ITEMS")
                .param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, first.get("rows").get(0).get("ID").asInt());
        String cursor = first.get("nextCursor").asText();
        jdbc.execute("DELETE FROM ITEMS WHERE ID=1");
        JsonNode second = mapper.readTree(mvc.perform(get("/db-web-view/api/rows").cookie(cookie)
                .param("source", source).param("namespace", "PUBLIC").param("table", "ITEMS")
                .param("page", "1").param("size", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(2, second.get("rows").get(0).get("ID").asInt());
    }

    @Test
    void tooManyLoginFailuresReturn429() throws Exception {
        for (int i = 0; i < 5; i++)
            mvc.perform(post("/db-web-view/api/login").with(request -> { request.setRemoteAddr("192.0.2.44"); return request; })
                    .contentType("application/json")
                    .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        mvc.perform(post("/db-web-view/api/login").with(request -> { request.setRemoteAddr("192.0.2.44"); return request; })
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void csvExportStreamsAcrossPagesWithoutDuplicates() throws Exception {
        for (int i = 2; i <= 205; i++) jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", i, "name-" + i);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        databaseService.writeCsv(output, "dataSource", "PUBLIC", "ITEMS",
                java.util.Collections.emptyMap(), null, false);
        String csv = output.toString("UTF-8");
        assertEquals(206, csv.split("\r\n").length);
        assertTrue(csv.contains("name-205"));
        assertEquals(1, csv.split("name-205", -1).length - 1);
    }

    @Test
    void multiConditionFilterAndCsvUseSameRows() throws Exception {
        jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", 2, "fir%st");
        jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", 3, "second");
        java.util.List<FilterCondition> filters = java.util.Arrays.asList(
                new FilterCondition("NAME", "contains", "%", null),
                new FilterCondition("ID", "between", "2", "3"));
        java.util.Map<String, Object> result = databaseService.rows("dataSource", "PUBLIC", "ITEMS", 0, 20, filters, null, false, null);
        assertEquals(1, ((java.util.List<?>) result.get("rows")).size());
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        databaseService.writeCsv(output, "dataSource", "PUBLIC", "ITEMS", filters, null, false);
        assertTrue(output.toString("UTF-8").contains("fir%st"));
        assertFalse(output.toString("UTF-8").contains("second"));
        jdbc.update("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)", 4, null);
        java.util.Map<String, Object> nullRows = databaseService.rows("dataSource", "PUBLIC", "ITEMS", 0, 20,
                java.util.Collections.singletonList(new FilterCondition("NAME", "isNull", null, null)), null, false, null);
        assertEquals(1, ((java.util.List<?>) nullRows.get("rows")).size());
        assertThrows(IllegalArgumentException.class, () -> databaseService.rows("dataSource", "PUBLIC", "ITEMS", 0, 20,
                java.util.Collections.singletonList(new FilterCondition("ID;DROP TABLE ITEMS", "eq", "1", null)), null, false, null));
    }

    @Test
    void filterAndExplainEndpointsAcceptNewRequests() throws Exception {
        MvcResult login = mvc.perform(post("/db-web-view/api/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = cookie(login);
        String csrf = mapper.readTree(login.getResponse().getContentAsString()).get("csrf").asText();
        String source = mapper.readTree(mvc.perform(get("/db-web-view/api/sources").cookie(cookie))
                .andReturn().getResponse().getContentAsString()).get("sources").get(0).asText();
        JsonNode filtered = mapper.readTree(mvc.perform(get("/db-web-view/api/rows").cookie(cookie)
                .param("source", source).param("namespace", "PUBLIC").param("table", "ITEMS")
                .param("filters", "[{\"column\":\"NAME\",\"operator\":\"contains\",\"value\":\"fir\"}]")
                .param("page", "0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, filtered.get("rows").size());
        java.util.Map<String, String> explain = new java.util.LinkedHashMap<>();
        explain.put("source", source);
        explain.put("sql", "SELECT * FROM ITEMS");
        mvc.perform(post("/db-web-view/api/sql/explain").cookie(cookie)
                .header("X-DB-Web-View-CSRF", csrf).contentType("application/json")
                .content(mapper.writeValueAsString(explain)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("resultSet"));
    }

    private Cookie cookie(MvcResult login) {
        String header = login.getResponse().getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
        assertNull(login.getRequest().getSession(false));
        String[] pair = header.split(";", 2)[0].split("=", 2);
        return new Cookie(pair[0], pair[1]);
    }

}
