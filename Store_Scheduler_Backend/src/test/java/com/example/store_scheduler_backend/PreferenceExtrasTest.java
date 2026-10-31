package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.DayOffFallback;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.NotificationRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 고정 일정 기간·종류, 이번 달만 예외, 지난달 불러오기, 계약 근무량·예상 급여, 정규직 연차 계획
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PreferenceExtrasTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);  // 30일, 11/2가 월요일
    private static final YearMonth DECEMBER = YearMonth.of(2026, 12);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;
    @Autowired NotificationRepository notificationRepository;

    private Store store;
    private Shift open;
    private Employee yuna;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("희망매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "EXTRACD1"));
        open = new Shift();
        open.setName("오픈");
        open.setStartTime(LocalTime.of(9, 0));
        open.setEndTime(LocalTime.of(14, 0));
        open.setRequiredStaff(1);
        open.setStore(store);
        shiftRepository.save(open);
        yuna = new Employee("유나", null, store, userRepository.save(new User("yuna", "pw", Role.EMPLOYEE)));
        yuna.approve(10_320);
        employeeRepository.save(yuna);
    }

    @Test
    void 고정_일정은_적용_기간에만_잠그고_이번_달_예외는_평일만_잠근다() throws Exception {
        as("yuna", put("/api/stores/{s}/fixed-schedules/my", store.getId())
                .contentType("application/json")
                .content("[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"10:30\",\"endTime\":\"13:30\",\"label\":\"전공 수업\","
                        + "\"category\":\"STUDY\",\"validFrom\":\"2026-09-01\",\"validUntil\":\"2026-11-15\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("STUDY"));
        as("yuna", put("/api/stores/{s}/unavailable-periods/my", store.getId())
                .contentType("application/json")
                .content("[{\"startDate\":\"2026-11-20\",\"endDate\":\"2026-11-22\",\"weekdaysOnly\":true,\"note\":\"시험 기간\"}]"))
                .andExpect(status().isOk());

        JsonNode view = body(as("yuna", get("/api/stores/{s}/preferences/my", store.getId()).param("month", NOVEMBER.toString())));
        assertThat(cell(view, "2026-11-09").get("lockLabel").asText()).isEqualTo("전공 수업");
        assertThat(cell(view, "2026-11-16").get("locked").asBoolean()).as("적용 기간이 끝난 월요일").isFalse();
        assertThat(cell(view, "2026-11-20").get("lockLabel").asText()).isEqualTo("시험 기간");
        assertThat(cell(view, "2026-11-21").get("locked").asBoolean()).as("토요일은 평일 예외가 아님").isFalse();
    }

    @Test
    void 지난달_답을_요일_교대_기준으로_불러오되_저장하지_않는다() throws Exception {
        as("yuna", put("/api/stores/{s}/preferences/my", store.getId())
                .param("month", NOVEMBER.toString())
                .contentType("application/json")
                .content("{\"slots\":[{\"date\":\"2026-11-02\",\"shiftId\":" + open.getId() + ",\"level\":\"PREFERRED\"},"
                        + "{\"date\":\"2026-11-03\",\"shiftId\":" + open.getId() + ",\"level\":\"AVAILABLE\"}],\"maxWeekendShifts\":2}"))
                .andExpect(status().isOk());

        JsonNode draft = body(as("yuna", get("/api/stores/{s}/preferences/my/draft", store.getId()).param("month", DECEMBER.toString())));
        assertThat(draft.get("submitted").asBoolean()).isFalse();
        assertThat(draft.get("submission").get("maxWeekendShifts").asInt()).isEqualTo(2);
        assertThat(cell(draft, "2026-12-07").get("level").asText()).as("월요일").isEqualTo("PREFERRED");
        assertThat(cell(draft, "2026-12-01").get("level").asText()).as("화요일").isEqualTo("AVAILABLE");
        assertThat(cell(draft, "2026-12-02").get("level").isNull()).as("수요일").isTrue();

        as("yuna", get("/api/stores/{s}/preferences/my", store.getId()).param("month", DECEMBER.toString()))
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.availableCount").value(0));
        as("yuna", get("/api/stores/{s}/preferences/my/draft", store.getId()).param("month", NOVEMBER.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 계약_범위로_이번_달_근무량과_예상_급여를_보여주고_상한을_넘기면_점주_승인이_필요하다() throws Exception {
        as("owner", patch("/api/stores/{s}/employees/{e}/profile", store.getId(), yuna.getId())
                .contentType("application/json").content("{\"contractMinWeeklyHours\":15,\"contractMaxWeeklyHours\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractMaxWeeklyHours").value(20));

        as("yuna", get("/api/stores/{s}/preferences/my/workload", store.getId()).param("month", NOVEMBER.toString()))
                .andExpect(jsonPath("$.contractMinMonthly").value(64))
                .andExpect(jsonPath("$.contractMaxMonthly").value(86))
                .andExpect(jsonPath("$.expectedPayMin").value(64 * 10_320))
                .andExpect(jsonPath("$.belowWeeklyHolidayThreshold").value(true))
                .andExpect(jsonPath("$.exceedsContract").value(false));
        as("yuna", get("/api/stores/{s}/preferences/my/workload", store.getId())
                        .param("month", NOVEMBER.toString()).param("maxMonthlyHours", "100"))
                .andExpect(jsonPath("$.exceedsContract").value(true));

        as("yuna", put("/api/stores/{s}/preferences/my", store.getId())
                .param("month", NOVEMBER.toString())
                .contentType("application/json").content("{\"slots\":[],\"minMonthlyHours\":64,\"maxMonthlyHours\":100}"))
                .andExpect(jsonPath("$.submission.contractExceedApproved").value(false));
        as("owner", get("/api/stores/{s}/preferences", store.getId()).param("month", NOVEMBER.toString()))
                .andExpect(jsonPath("$.employees[0].needsContractApproval").value(true));
        as("owner", post("/api/stores/{s}/preferences/{e}/contract-exceed", store.getId(), yuna.getId())
                .param("month", NOVEMBER.toString()).param("approved", "true"))
                .andExpect(status().isOk());
        as("owner", get("/api/stores/{s}/preferences", store.getId()).param("month", NOVEMBER.toString()))
                .andExpect(jsonPath("$.employees[0].needsContractApproval").value(false));
    }

    @Test
    void 계약_초과는_대기와_거절을_구분하고_양쪽에_알리며_같은_시간으로_다시_내도_결정을_유지한다() throws Exception {
        as("owner", patch("/api/stores/{s}/employees/{e}/profile", store.getId(), yuna.getId())
                .contentType("application/json").content("{\"contractMinWeeklyHours\":15,\"contractMaxWeeklyHours\":20}"))
                .andExpect(status().isOk());

        submitMaxHours(100).andExpect(jsonPath("$.submission.contractExceedStatus").value("PENDING"));
        as("owner", get("/api/stores/{s}/preferences", store.getId()).param("month", NOVEMBER.toString()))
                .andExpect(jsonPath("$.employees[0].contractExceedStatus").value("PENDING"))
                .andExpect(jsonPath("$.employees[0].requestedMaxMonthlyHours").value(100))
                .andExpect(jsonPath("$.employees[0].contractMaxMonthlyHours").value(86));
        as("owner", get("/api/stores/{s}/home/owner", store.getId()).param("date", "2026-11-01"))
                .andExpect(jsonPath("$.pendingContractApprovals").value(1));
        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("owner"))
                .singleElement().satisfies(n -> assertThat(n.getTitle()).isEqualTo("계약 초과 근무 승인 필요"));

        as("owner", post("/api/stores/{s}/preferences/{e}/contract-exceed", store.getId(), yuna.getId())
                .param("month", NOVEMBER.toString()).param("approved", "false"))
                .andExpect(status().isOk());
        as("owner", get("/api/stores/{s}/preferences", store.getId()).param("month", NOVEMBER.toString()))
                .andExpect(jsonPath("$.employees[0].needsContractApproval").value(false))
                .andExpect(jsonPath("$.employees[0].contractExceedStatus").value("REJECTED"));
        as("owner", get("/api/stores/{s}/home/owner", store.getId()).param("date", "2026-11-01"))
                .andExpect(jsonPath("$.pendingContractApprovals").value(0));
        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("yuna"))
                .anySatisfy(n -> assertThat(n.getTitle()).isEqualTo("계약 초과 근무 거절"));

        submitMaxHours(100).andExpect(jsonPath("$.submission.contractExceedStatus").value("REJECTED"));
        submitMaxHours(90).andExpect(jsonPath("$.submission.contractExceedStatus").value("PENDING"));
        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("owner")).hasSize(2);
        submitMaxHours(80).andExpect(jsonPath("$.submission.contractExceedStatus").doesNotExist());
    }

    @Test
    void 정규직은_휴무_대안에_둔_연차와_반차만큼_최대_사용량을_계산한다() throws Exception {
        Employee jisu = new Employee("지수", null, store, userRepository.save(new User("jisu", "pw", Role.EMPLOYEE)));
        jisu.fillJoinProfile(null, LocalDate.now().minusYears(2).minusMonths(1), false, EmploymentType.FULL_TIME);
        jisu.approve(12_000);
        employeeRepository.save(jisu);
        YearMonth next = YearMonth.now().plusMonths(1);
        dayOffRequestRepository.save(new DayOffRequest(jisu, next.atDay(13), DayOffType.PREFER, "가족 여행",
                List.of(DayOffFallback.ANNUAL_LEAVE)));
        dayOffRequestRepository.save(new DayOffRequest(jisu, next.atDay(26), DayOffType.PREFER, "병원",
                List.of(DayOffFallback.HALF_DAY, DayOffFallback.SWAP_DAY)));

        as("jisu", get("/api/stores/{s}/preferences/my/leave-plan", store.getId()).param("month", next.toString()))
                .andExpect(jsonPath("$.remaining").value(15))
                .andExpect(jsonPath("$.maxUse").value(1.5))
                .andExpect(jsonPath("$.minRemaining").value(13.5));
        as("yuna", get("/api/stores/{s}/preferences/my/leave-plan", store.getId()).param("month", next.toString()))
                .andExpect(jsonPath("$.remaining").doesNotExist());
    }

    private JsonNode cell(JsonNode view, String date) {
        for (JsonNode c : view.get("cells")) {
            if (c.get("date").asText().equals(date)) {
                return c;
            }
        }
        throw new AssertionError("칸 없음: " + date);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions submitMaxHours(int hours) throws Exception {
        return as("yuna", put("/api/stores/{s}/preferences/my", store.getId())
                .param("month", NOVEMBER.toString())
                .contentType("application/json").content("{\"slots\":[],\"maxMonthlyHours\":" + hours + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
