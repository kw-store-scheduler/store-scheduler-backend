package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.ClosingReport;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.ClosingReportRepository;
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
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 마감 보고 → 다음 달 요일·교대별 필요 인원 제안
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffingSuggestionTest {

    // 이번 달을 기준 달로 쓰면 직전 4주가 모두 과거라 미래 날짜 보고 제한에 걸리지 않는다
    private static final YearMonth MONTH = YearMonth.now();
    private static final LocalDate BASE_TO = MONTH.atDay(1).minusDays(1);
    private static final LocalDate BASE_FROM = BASE_TO.minusWeeks(4).plusDays(1);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ClosingReportRepository closingReportRepository;

    private Store store;
    private Shift open;
    private Shift saturdayExtra;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(new User("owner", "pw", Role.MANAGER));
        store = storeRepository.save(new Store("테스트매장", null, owner, "STAFFCD1"));
        open = shift("오픈", 9, 13, 1, null);
        saturdayExtra = shift("토요특근", 13, 17, 2, DayOfWeek.SATURDAY);

        // 기준 4주 동안 월요일 오픈 70명, 화요일 오픈 20명, 토요특근 30명
        for (LocalDate d = BASE_FROM; !d.isAfter(BASE_TO); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.MONDAY) {
                closingReportRepository.save(new ClosingReport(open, d, 70, null));
            } else if (d.getDayOfWeek() == DayOfWeek.TUESDAY) {
                closingReportRepository.save(new ClosingReport(open, d, 20, null));
            } else if (d.getDayOfWeek() == DayOfWeek.SATURDAY) {
                closingReportRepository.save(new ClosingReport(saturdayExtra, d, 30, null));
            }
        }
        // 기준 기간 밖의 보고는 반영하지 않는다
        closingReportRepository.save(new ClosingReport(open, BASE_FROM.with(TemporalAdjusters.previous(DayOfWeek.MONDAY)), 500, null));
    }

    @Test
    void 직전_4주_평균_객수로_요일_교대별_인원을_제안한다() throws Exception {
        JsonNode result = suggest();

        assertThat(result.get("customersPerStaff").asInt()).isEqualTo(30);
        assertThat(result.get("basedOnFrom").asText()).isEqualTo(BASE_FROM.toString());
        JsonNode monday = find(result, "오픈", "MONDAY");
        assertThat(monday.get("sampleDays").asInt()).isEqualTo(4);
        assertThat(monday.get("averageCustomers").asDouble()).isEqualTo(70.0);
        assertThat(monday.get("suggestedStaff").asInt()).isEqualTo(3);  // 70 / 30 올림
        assertThat(monday.get("reason").asText()).contains("2명 증원");
        assertThat(find(result, "오픈", "TUESDAY").get("reason").asText()).contains("유지");
        assertThat(find(result, "토요특근", "SATURDAY").get("reason").asText()).contains("1명 감원");
        assertThat(find(result, "오픈", "WEDNESDAY").get("suggestedStaff").isNull()).isTrue();
        // 요일 전용 교대는 그 요일만 나온다
        assertThat(result.get("suggestions").size()).isEqualTo(7 + 1);
    }

    @Test
    void 일인당_객수_설정을_바꾸면_제안도_바뀐다() throws Exception {
        as("owner", patch("/api/stores/{storeId}/settings", store.getId())
                .contentType("application/json").content("{\"customersPerStaff\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customersPerStaff").value(40))
                .andExpect(jsonPath("$.guaranteeMustDayOff").value(false));

        assertThat(find(suggest(), "오픈", "MONDAY").get("suggestedStaff").asInt()).isEqualTo(2);  // 70 / 40 올림
    }

    @Test
    void 마감_보고는_같은_날짜_교대면_덮어쓰고_미래_날짜는_거절한다() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        report(open, yesterday, 10).andExpect(status().isOk());
        report(open, yesterday, 25).andExpect(status().isOk()).andExpect(jsonPath("$.customerCount").value(25));
        assertThat(closingReportRepository.findByShiftIdAndDate(open.getId(), yesterday)).get()
                .extracting(ClosingReport::getCustomerCount).isEqualTo(25);

        report(open, LocalDate.now().plusDays(1), 10).andExpect(status().isBadRequest());
    }

    @Test
    void 다른_매장_관리자는_보고하거나_제안을_볼_수_없다() throws Exception {
        userRepository.save(new User("other", "pw", Role.MANAGER));
        as("other", get("/api/stores/{storeId}/staffing-suggestions", store.getId()).param("month", MONTH.toString()))
                .andExpect(status().isForbidden());
        as("other", post("/api/stores/{storeId}/closing-reports", store.getId())
                .contentType("application/json")
                .content("{\"shiftId\":" + open.getId() + ",\"date\":\"" + BASE_TO + "\",\"customerCount\":10}"))
                .andExpect(status().isForbidden());
    }

    private JsonNode suggest() throws Exception {
        String body = as("owner", get("/api/stores/{storeId}/staffing-suggestions", store.getId()).param("month", MONTH.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions report(Shift shift, LocalDate date, int customers) throws Exception {
        return as("owner", post("/api/stores/{storeId}/closing-reports", store.getId())
                .contentType("application/json")
                .content("{\"shiftId\":" + shift.getId() + ",\"date\":\"" + date + "\",\"customerCount\":" + customers + "}"));
    }

    private JsonNode find(JsonNode result, String shiftName, String day) {
        for (JsonNode s : result.get("suggestions")) {
            if (s.get("shiftName").asText().equals(shiftName) && s.get("dayOfWeek").asText().equals(day)) {
                return s;
            }
        }
        throw new AssertionError("제안 없음: " + shiftName + " " + day);
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }

    private Shift shift(String name, int startHour, int endHour, int requiredStaff, DayOfWeek dayOfWeek) {
        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(LocalTime.of(startHour, 0));
        shift.setEndTime(LocalTime.of(endHour, 0));
        shift.setRequiredStaff(requiredStaff);
        shift.setDayOfWeek(dayOfWeek);
        shift.setStore(store);
        return shiftRepository.save(shift);
    }
}
