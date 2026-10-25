package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 역량 신청 → 매니저 확정/유지, 혼자 맡길 수 있는 일·수습, 변경 이력, 시간대별 필요 역량
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SkillProfileTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired SkillRepository skillRepository;

    private Store store;
    private Employee yuna;
    private Skill chinese;
    private Skill english;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("역량매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "SKILLCD1"));
        yuna = new Employee("김유나", null, store, userRepository.save(new User("yuna", "pw", Role.EMPLOYEE)));
        yuna.approve(10_320);
        employeeRepository.save(yuna);
        chinese = skillRepository.save(new Skill("중국어", store));
        english = skillRepository.save(new Skill("영어", store));
    }

    @Test
    void 직원이_신청한_등급은_매니저가_확정하기_전까지_확인_대기이고_확정하면_이력이_남는다() throws Exception {
        as("yuna", put("/api/stores/{s}/my-skills", store.getId())
                .contentType("application/json")
                .content("[{\"skillId\":" + chinese.getId() + ",\"level\":\"HIGH\"},{\"skillId\":" + english.getId() + ",\"level\":\"MID\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].status").value("PENDING"))
                .andExpect(jsonPath("$.skills[0].level").value("NONE"))
                .andExpect(jsonPath("$.skills[0].requestedLevel").value("HIGH"));
        assertThat(yuna.getSkills()).isEmpty();

        as("owner", get("/api/stores/{s}/skill-requests", store.getId()))
                .andExpect(jsonPath("$.length()").value(2));

        as("owner", put("/api/stores/{s}/employees/{e}/skills/{k}/level", store.getId(), yuna.getId(), chinese.getId())
                .contentType("application/json").content("{\"level\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].level").value("HIGH"))
                .andExpect(jsonPath("$.history[0].description").value("중국어 없음 → 상 확정"));
        assertThat(yuna.getSkills()).extracting(Skill::getName).containsExactly("중국어");

        as("owner", post("/api/stores/{s}/employees/{e}/skills/{k}/keep", store.getId(), yuna.getId(), english.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[1].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.history[0].description").value("영어 신청은 없음(으)로 유지"));
        as("owner", get("/api/stores/{s}/skill-requests", store.getId()))
                .andExpect(jsonPath("$.length()").value(0));
        as("owner", get("/api/stores/{s}/skill-matrix", store.getId()))
                .andExpect(jsonPath("$[0].skills[0].level").value("HIGH"));
    }

    @Test
    void 혼자_맡길_수_있는_일과_수습_기간은_매니저가_정하고_시급_변경도_이력에_남는다() throws Exception {
        as("owner", patch("/api/stores/{s}/employees/{e}/profile", store.getId(), yuna.getId())
                .contentType("application/json")
                .content("{\"permissions\":[\"SOLO_OPEN\",\"STORE_KEY\"],\"probationUntil\":\"2026-11-30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probationUntil").value("2026-11-30"))
                .andExpect(jsonPath("$.permissions[0].allowed").value(true))
                .andExpect(jsonPath("$.permissions[1].allowed").value(false));
        as("owner", patch("/api/stores/{s}/employees/{e}", store.getId(), yuna.getId())
                .contentType("application/json").content("{\"hourlyWage\":10500}"))
                .andExpect(status().isOk());

        as("owner", get("/api/stores/{s}/employees/{e}/profile", store.getId(), yuna.getId()))
                .andExpect(jsonPath("$.history[0].description").value("시급 10320원 → 10500원"))
                .andExpect(jsonPath("$.history.length()").value(4));
        as("yuna", get("/api/stores/{s}/my-skills", store.getId()))
                .andExpect(jsonPath("$.permissions[0].code").value("SOLO_OPEN"))
                .andExpect(jsonPath("$.permissions[0].allowed").value(true));
        as("yuna", patch("/api/stores/{s}/employees/{e}/profile", store.getId(), yuna.getId())
                .contentType("application/json").content("{\"clearProbation\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 시간대별_필요_역량은_스킬이나_맡길_수_있는_일_중_하나로_등록한다() throws Exception {
        as("owner", post("/api/stores/{s}/skill-windows", store.getId())
                .contentType("application/json")
                .content("{\"label\":\"피크\",\"startTime\":\"12:00\",\"endTime\":\"14:00\",\"skillId\":" + chinese.getId()
                        + ",\"minLevel\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mandatory").value(true))
                .andExpect(jsonPath("$.days.length()").value(0));
        String body = as("owner", post("/api/stores/{s}/skill-windows", store.getId())
                        .contentType("application/json")
                        .content("{\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"permission\":\"ORDERING\",\"mandatory\":false,"
                                + "\"days\":[\"TUESDAY\",\"FRIDAY\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[1]").value("FRIDAY"))
                .andReturn().getResponse().getContentAsString();
        as("owner", post("/api/stores/{s}/skill-windows", store.getId())
                .contentType("application/json")
                .content("{\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"permission\":\"ORDERING\",\"skillId\":" + chinese.getId() + "}"))
                .andExpect(status().isBadRequest());

        as("yuna", get("/api/stores/{s}/skill-windows", store.getId())).andExpect(jsonPath("$.length()").value(2));
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        as("owner", delete("/api/stores/{s}/skill-windows/{id}", store.getId(), id)).andExpect(status().isNoContent());
        as("owner", delete("/api/stores/{s}/skills/{id}", store.getId(), chinese.getId())).andExpect(status().isOk());
        as("yuna", get("/api/stores/{s}/skill-windows", store.getId())).andExpect(jsonPath("$.length()").value(0));
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
