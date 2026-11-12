package io.github.guanpeng.dbwebview;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DynamicDataSourceIntegrationTest.App.class, properties = {
        "db-web-view.username=admin", "db-web-view.password=secret",
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
    static class App {
        @Bean DataSource dataSource() {
            DynamicRoutingDataSource routing = new DynamicRoutingDataSource();
            routing.add("szqdpt", new DriverManagerDataSource("jdbc:h2:mem:szqdpt_api;DB_CLOSE_DELAY=-1", "sa", ""));
            return routing;
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void sourcesRemainAvailableAfterReloadingSession() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/db-web-view/api/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/db-web-view/api/session").session(session)).andExpect(status().isOk());
        mvc.perform(get("/db-web-view/api/sources").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("szqdpt")));
        mvc.perform(get("/db-web-view/api/tables").session(session).param("source", "szqdpt"))
                .andExpect(status().isOk());
    }
}
