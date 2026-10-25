package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.ClosingReport;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.ClosingReportRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 로그인 → 역할 선택 → 매장 등록(업종) → 교대 손보기 → 직원 합류 신청 → 승인 대기 목록
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StoreOnboardingTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired SkillRepository skillRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ClosingReportRepository closingReportRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("owner", "pw", Role.MANAGER));
        userRepository.save(new User("staff", "pw", Role.EMPLOYEE));
    }

    @Test
    void 업종을_고르면_업종별_스킬과_기본_교대가_만들어진다() throws Exception {
        as("owner", get("/api/stores/industries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CAFE"))
                .andExpect(jsonPath("$[0].label").value("카페"))
                .andExpect(jsonPath("$[0].shifts.length()").value(3));

        long storeId = createStore("구시대 커피 광운대점", "CAFE");

        assertThat(skillRepository.findByStoreId(storeId)).extracting("name")
                .containsExactlyInAnyOrder("음료 제조", "응대 속도", "오픈·마감", "외국어", "정산");
        as("owner", get("/api/stores/{storeId}/shifts", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        as("owner", get("/api/stores"))
                .andExpect(jsonPath("$[0].industry").value("CAFE"));
    }

    @Test
    void 업종을_생략하면_기타로_보고_아무것도_만들지_않는다() throws Exception {
        long storeId = createStore("이름만 매장", null);

        assertThat(skillRepository.findByStoreId(storeId)).isEmpty();
        assertThat(shiftRepository.findByStoreId(storeId)).isEmpty();
        as("owner", get("/api/stores")).andExpect(jsonPath("$[0].industry").value("ETC"));
    }

    @Test
    void 기본_교대를_수정하고_삭제할_수_있지만_마감_보고가_있으면_삭제하지_않는다() throws Exception {
        long storeId = createStore("카페", "CAFE");
        Shift open = shiftRepository.findByStoreId(storeId).stream().filter(s -> s.getName().equals("오픈")).findFirst().orElseThrow();
        Shift middle = shiftRepository.findByStoreId(storeId).stream().filter(s -> s.getName().equals("미들")).findFirst().orElseThrow();

        as("owner", put("/api/stores/{storeId}/shifts/{shiftId}", storeId, open.getId())
                .contentType("application/json")
                .content("{\"name\":\"오픈\",\"startTime\":\"08:00\",\"endTime\":\"13:00\",\"requiredStaff\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startTime").value("08:00:00"))
                .andExpect(jsonPath("$.requiredStaff").value(2));

        as("owner", delete("/api/stores/{storeId}/shifts/{shiftId}", storeId, middle.getId()))
                .andExpect(status().isNoContent());
        assertThat(shiftRepository.findByStoreId(storeId)).hasSize(2);

        closingReportRepository.save(new ClosingReport(open, LocalDate.now().minusDays(1), 30, null));
        as("owner", delete("/api/stores/{storeId}/shifts/{shiftId}", storeId, open.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 요일별_인원과_예외일을_설정하고_매장_설정으로_쪼개기_근무와_제출_마감일을_정한다() throws Exception {
        long storeId = createStore("카페", "CAFE");
        Shift open = shiftRepository.findByStoreId(storeId).stream().filter(s -> s.getName().equals("오픈")).findFirst().orElseThrow();

        as("owner", put("/api/stores/{storeId}/shifts/{shiftId}/day-staffing", storeId, open.getId())
                .contentType("application/json")
                .content("[{\"dayOfWeek\":\"SATURDAY\",\"minStaff\":2,\"targetStaff\":3},{\"dayOfWeek\":\"SUNDAY\",\"minStaff\":2}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        as("owner", get("/api/stores/{storeId}/shifts", storeId))
                .andExpect(jsonPath("$[?(@.name=='오픈')].dayStaffing[1].targetStaff").value(2));
        as("owner", put("/api/stores/{storeId}/shifts/{shiftId}/day-staffing", storeId, open.getId())
                .contentType("application/json").content("[{\"dayOfWeek\":\"MONDAY\",\"minStaff\":3,\"targetStaff\":1}]"))
                .andExpect(status().isBadRequest());

        LocalDate day = LocalDate.of(2026, 12, 24);
        as("owner", post("/api/stores/{storeId}/staffing-exceptions", storeId)
                .contentType("application/json")
                .content("{\"date\":\"" + day + "\",\"minStaff\":0,\"note\":\"휴점\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftId").doesNotExist());
        as("owner", post("/api/stores/{storeId}/staffing-exceptions", storeId)
                .contentType("application/json")
                .content("{\"date\":\"" + day + "\",\"minStaff\":0}"))
                .andExpect(status().isBadRequest());
        String list = as("owner", get("/api/stores/{storeId}/staffing-exceptions", storeId)
                        .param("from", day.toString()).param("to", day.toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        long exceptionId = objectMapper.readTree(list).get(0).get("id").asLong();
        as("owner", delete("/api/stores/{storeId}/staffing-exceptions/{id}", storeId, exceptionId))
                .andExpect(status().isNoContent());

        as("owner", patch("/api/stores/{storeId}/settings", storeId)
                .contentType("application/json").content("{\"allowSplitShift\":true,\"preferenceDeadlineDay\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowSplitShift").value(true))
                .andExpect(jsonPath("$.preferenceDeadlineDay").value(20));
        as("owner", patch("/api/stores/{storeId}/settings", storeId)
                .contentType("application/json").content("{\"preferenceDeadlineDay\":31}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 직원은_코드로_매장을_미리_보고_가입_정보와_함께_신청한다() throws Exception {
        long storeId = createStore("구시대 커피 광운대점", "CAFE");
        String code = storeCode();

        as("staff", get("/api/stores/code/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(storeId))
                .andExpect(jsonPath("$.industryLabel").value("카페"))
                .andExpect(jsonPath("$.employeeCount").value(0));
        as("staff", get("/api/stores/code/{code}", "WRONG000")).andExpect(status().isBadRequest());

        as("staff", post("/api/stores/join/{code}", code)
                .contentType("application/json")
                .content("{\"name\":\"김남용\",\"nickname\":\"남용\",\"startDate\":\"2026-11-20\",\"minor\":true,"
                        + "\"employmentType\":\"FULL_TIME\"}"))
                .andExpect(status().isOk());

        as("owner", get("/api/stores/{storeId}/employees/pending", storeId))
                .andExpect(jsonPath("$[0].nickname").value("남용"))
                .andExpect(jsonPath("$[0].startDate").value("2026-11-20"))
                .andExpect(jsonPath("$[0].minor").value(true))
                .andExpect(jsonPath("$[0].employmentType").value("FULL_TIME"))
                .andExpect(jsonPath("$[0].requestedAt").exists());
    }

    @Test
    void 내_정보에_역할과_소유_매장_합류_상태가_나온다() throws Exception {
        long storeId = createStore("카페", "CAFE");
        as("staff", post("/api/stores/join/{code}", storeCode())
                .contentType("application/json").content("{\"name\":\"김남용\"}"))
                .andExpect(status().isOk());

        as("owner", get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.ownedStores[0].id").value(storeId))
                .andExpect(jsonPath("$.memberships.length()").value(0));
        as("staff", get("/api/users/me"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.memberships[0].storeId").value(storeId))
                .andExpect(jsonPath("$.memberships[0].status").value("PENDING"))
                .andExpect(jsonPath("$.memberships[0].employmentType").value("PART_TIME"));
    }

    @Test
    void 매장이나_합류_신청이_없을_때만_역할을_바꾸고_새_토큰을_준다() throws Exception {
        userRepository.save(new User("newbie", "pw", Role.EMPLOYEE));
        as("newbie", patch("/api/users/me/role").contentType("application/json").content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
        assertThat(userRepository.findByUsername("newbie").orElseThrow().getRole()).isEqualTo(Role.MANAGER);

        createStore("카페", null);
        as("owner", patch("/api/users/me/role").contentType("application/json").content("{\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isBadRequest());
    }

    private long createStore(String name, String industry) throws Exception {
        String industryField = industry != null ? ",\"industry\":\"" + industry + "\"" : "";
        String body = as("owner", post("/api/stores")
                .contentType("application/json")
                .content("{\"name\":\"" + name + "\"" + industryField + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private String storeCode() throws Exception {
        String body = as("owner", get("/api/stores")).andReturn().getResponse().getContentAsString();
        JsonNode stores = objectMapper.readTree(body);
        return stores.get(0).get("storeCode").asText();
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
