package io.github.g1335333249.dbwebview;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import javax.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DynamicDataSourceIntegrationTest.App.class, properties = {
        "db-web-view.username=admin", "db-web-view.password=secret",
        "db-web-view.jwt-secret=abcdefghijklmnopqrstuvwxyz0123456789-SharedSecret",
        "spring.datasource.dynamic.primary=szqdpt",
        "spring.datasource.dynamic.datasource.szqdpt.driver-class-name=org.h2.Driver",
        "spring.datasource.dynamic.datasource.szqdpt.username=sa",
        "spring.datasource.dynamic.datasource.szqdpt.password=",
        "spring.datasource.dynamic.datasource.szqdpt.url=jdbc:h2:mem:szqdpt_api;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class DynamicDataSourceIntegrationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App { }

    @Autowired MockMvc mvc;

    @Test
    void sourcesRemainAvailableAfterReloadingSession() throws Exception {
        MvcResult login = mvc.perform(post("/db-web-view/api/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = login.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        Cookie cookie = new Cookie("DBWV_AUTH", token);
        mvc.perform(get("/db-web-view/api/session").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/db-web-view/api/sources").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(containsString("szqdpt")));
        mvc.perform(get("/db-web-view/api/tables").cookie(cookie).param("source", "szqdpt"))
                .andExpect(status().isOk());
    }
}
