package com.example.store_scheduler_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Swagger 문서 생성과 브라우저 CORS preflight가 동작하는지 확인
@SpringBootTest
@AutoConfigureMockMvc
class ApiAccessConfigTest {

    @Autowired MockMvc mockMvc;

    @Test
    void API_문서에_엔드포인트와_JWT_인증_방식이_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.JWT.scheme").value("bearer"));
    }

    @Test
    void 허용된_출처의_preflight는_PATCH까지_통과한다() throws Exception {
        mockMvc.perform(options("/api/users/me/device-token")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
    }

    @Test
    void 허용되지_않은_출처의_preflight는_거절한다() throws Exception {
        mockMvc.perform(options("/api/users/me/device-token")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
    }
}
