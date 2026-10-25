package com.example.store_scheduler_backend;

import org.junit.jupiter.api.Test;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Swagger 문서 생성과 브라우저 CORS preflight가 동작하는지 확인
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiAccessConfigTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;

    @Test
    void API_문서에_엔드포인트와_JWT_인증_방식이_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.JWT.scheme").value("bearer"));
    }

    @Test
    void 서버_주소와_swagger_ui_html로_접속하면_API_문서로_이동한다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
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
    void Flutter_웹_디버그처럼_임의_localhost_포트의_preflight도_통과한다() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:54321")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }

    @Test
    void 토큰이_없으면_401_권한이_없으면_403을_메시지와_함께_준다() throws Exception {
        mockMvc.perform(get("/api/stores"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("로그인이 필요합니다")));
        userRepository.save(new User("staff", "pw", Role.EMPLOYEE));
        mockMvc.perform(get("/api/stores").header("Authorization", "Bearer " + jwtTokenProvider.createToken("staff", "EMPLOYEE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
    }

    @Test
    void 사라진_사용자의_토큰은_500이_아니라_401이다() throws Exception {
        mockMvc.perform(get("/api/stores").header("Authorization", "Bearer " + jwtTokenProvider.createToken("deleted", "MANAGER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 허용되지_않은_출처의_preflight는_거절한다() throws Exception {
        mockMvc.perform(options("/api/users/me/device-token")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
    }
}
