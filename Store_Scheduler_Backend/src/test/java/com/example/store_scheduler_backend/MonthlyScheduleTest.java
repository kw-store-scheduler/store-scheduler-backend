package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 희망 제출 → 월간 근무표 생성(실제 엔진) → 확정 → 내 근무표 배정 사유.
 * python3 + ortools가 없는 환경에서는 건너뛴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MonthlyScheduleTest {

    private static final YearMonth MONTH = YearMonth.now().plusMonths(1);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private Store store;
    private Shift open;
    private Shift close;
    private Employee opener;
    private Employee student;
    private Employee legacy;

    @BeforeAll
    static void requireEngine() {
        assumeTrue(commandSucceeds("python3", "-c", "import ortools"), "python3/ortools가 없어 건너뜀");
    }

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("월간매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "MONTHCD1"));
        open = shift("오픈", 9, 14);
        close = shift("마감", 17, 22);
        opener = employee("opener", "오픈러", 10_000);
        student = employee("student", "학생", 11_000);
        // 희망을 내지 않은 직원은 기존처럼 요일별 가용 시간으로 배정한다
        legacy = employee("legacy", "기존", 12_000);
        for (DayOfWeek day : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(legacy, day, LocalTime.of(0, 0), LocalTime.of(23, 59)));
        }
    }

    @Test
    void 고정_일정과_겹치는_칸은_잠기고_제출할_수_없다() throws Exception {
        as("student", put("/api/stores/{storeId}/fixed-schedules/my", store.getId())
                .contentType("application/json")
                .content("[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"08:00\",\"endTime\":\"12:00\",\"label\":\"수업\"}]"))
                .andExpect(status().isOk());

        JsonNode view = body(as("student", get("/api/stores/{storeId}/preferences/my", store.getId()).param("month", MONTH.toString())));
        LocalDate monday = firstOf(DayOfWeek.MONDAY);
        JsonNode mondayOpen = cell(view, monday, open);
        assertThat(mondayOpen.get("locked").asBoolean()).isTrue();
        assertThat(mondayOpen.get("lockLabel").asText()).isEqualTo("수업");
        assertThat(cell(view, monday, close).get("locked").asBoolean()).isFalse();
        assertThat(view.get("submitted").asBoolean()).isFalse();

        submit("student", List.of(slot(monday, open, "AVAILABLE")), Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void 희망을_반영해_한_달_치_후보안을_만들고_확정하면_배정_사유가_남는다() throws Exception {
        as("student", put("/api/stores/{storeId}/fixed-schedules/my", store.getId())
                .contentType("application/json")
                .content("[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"08:00\",\"endTime\":\"12:00\",\"label\":\"수업\"}]"))
                .andExpect(status().isOk());

        // 오픈러: 평일 오픈 선호, 마감은 매일 가능 / 학생: 월요일 오픈 빼고 전부 가능, 주말은 한 달 2회까지
        // 주말 두 교대는 기존 직원과 오픈러로 채울 수 있어야 학생의 주말 한도를 지킬 수 있다
        List<Map<String, Object>> openerSlots = new ArrayList<>();
        List<Map<String, Object>> studentSlots = new ArrayList<>();
        for (LocalDate d = MONTH.atDay(1); !d.isAfter(MONTH.atEndOfMonth()); d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend) {
                openerSlots.add(slot(d, open, "PREFERRED"));
            }
            openerSlots.add(slot(d, close, "AVAILABLE"));
            if (d.getDayOfWeek() != DayOfWeek.MONDAY) {
                studentSlots.add(slot(d, open, "AVAILABLE"));
            }
            studentSlots.add(slot(d, close, "AVAILABLE"));
        }
        submit("opener", openerSlots, Map.of("priorities", List.of("SHIFT", "DAY_OFF"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.submission.priorities[0]").value("SHIFT"));
        submit("student", studentSlots, Map.of("maxWeekendShifts", 2)).andExpect(status().isOk());

        as("owner", get("/api/stores/{storeId}/preferences", store.getId()).param("month", MONTH.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedCount").value(2))
                .andExpect(jsonPath("$.employeeCount").value(3));

        JsonNode proposal = body(as("owner", post("/api/stores/{storeId}/schedules/automate", store.getId())
                .param("month", MONTH.toString())));
        assertThat(proposal.get("success").asBoolean()).isTrue();
        assertThat(proposal.get("weekStart").asText()).isEqualTo(MONTH.atDay(1).toString());
        assertThat(proposal.get("periodEnd").asText()).isEqualTo(MONTH.atEndOfMonth().toString());

        JsonNode preferred = candidateLabeled(proposal.get("candidates"), "희망 우선");
        JsonNode metrics = preferred.get("metrics");
        assertThat(metrics.get("preferenceRate").isNull()).isFalse();
        assertThat(metrics.get("laborLawViolations").asInt()).isZero();
        assertThat(metrics.get("minStaffCoverage").asDouble()).isEqualTo(100.0);

        int studentWeekends = 0;
        for (JsonNode a : preferred.get("assignments")) {
            LocalDate date = LocalDate.parse(a.get("workDate").asText());
            long employeeId = a.get("employeeId").asLong();
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (employeeId == opener.getId()) {
                assertThat(weekend && a.get("shiftName").asText().equals("오픈"))
                        .as("오픈러는 주말 오픈 칸을 내지 않았다").isFalse();
            }
            if (employeeId == student.getId()) {
                assertThat(date.getDayOfWeek() == DayOfWeek.MONDAY && a.get("shiftName").asText().equals("오픈"))
                        .as("학생은 월요일 수업 시간 오픈에 배정되지 않는다").isFalse();
                studentWeekends += weekend ? 1 : 0;
            }
        }
        assertThat(studentWeekends).isLessThanOrEqualTo(2);

        as("owner", post("/api/stores/{storeId}/schedules/proposals/{id}/confirm", store.getId(), proposal.get("proposalId").asLong())
                .contentType("application/json").content("{\"candidateIndex\":" + preferred.get("index").asInt() + "}"))
                .andExpect(status().isOk());

        List<Schedule> saved = scheduleRepository.findByStoreIdAndWorkDateBetween(store.getId(), MONTH.atDay(1), MONTH.atEndOfMonth());
        assertThat(saved).hasSize(preferred.get("assignments").size());
        JsonNode mine = body(as("opener", get("/api/stores/{storeId}/schedules/my", store.getId())));
        assertThat(mine.size()).isPositive();
        List<String> reasons = new ArrayList<>();
        mine.forEach(s -> s.get("reasons").forEach(r -> reasons.add(r.asText())));
        assertThat(reasons).anyMatch(r -> r.equals("선호한 칸") || r.equals("가능하다고 한 칸"));
        assertThat(reasons).anyMatch(r -> r.startsWith("이번 달 "));

        JsonNode report = body(as("opener", get("/api/stores/{storeId}/preference-report/my", store.getId())
                .param("month", MONTH.toString())));
        assertThat(report.get("assignedCount").asInt()).isEqualTo(mine.size());
        assertThat(report.get("preferenceRate").isNull()).isFalse();
        assertThat(report.get("burdens")).extracting(b -> b.get("kind").asText()).containsExactly("WEEKEND", "CLOSE", "NIGHT");
        assertThat(report.get("unfulfilled").size()).isEqualTo(report.get("assignedCount").asInt() - report.get("honoredCount").asInt());
    }

    private Map<String, Object> slot(LocalDate date, Shift shift, String level) {
        return Map.of("date", date.toString(), "shiftId", shift.getId(), "level", level);
    }

    private ResultActions submit(String username, List<Map<String, Object>> slots, Map<String, Object> rules) throws Exception {
        Map<String, Object> request = new HashMap<>(rules);
        request.put("slots", slots);
        return as(username, put("/api/stores/{storeId}/preferences/my", store.getId())
                .param("month", MONTH.toString())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)));
    }

    private JsonNode cell(JsonNode view, LocalDate date, Shift shift) {
        for (JsonNode c : view.get("cells")) {
            if (c.get("date").asText().equals(date.toString()) && c.get("shiftId").asLong() == shift.getId()) {
                return c;
            }
        }
        throw new AssertionError("칸 없음: " + date + " " + shift.getName());
    }

    private JsonNode candidateLabeled(JsonNode candidates, String label) {
        for (JsonNode c : candidates) {
            if (List.of(c.get("label").asText().split(" · ")).contains(label)) {
                return c;
            }
        }
        throw new AssertionError("후보안 없음: " + label);
    }

    private LocalDate firstOf(DayOfWeek dayOfWeek) {
        LocalDate d = MONTH.atDay(1);
        while (d.getDayOfWeek() != dayOfWeek) {
            d = d.plusDays(1);
        }
        return d;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Employee employee(String username, String name, int wage) {
        Employee employee = new Employee(name, null, store, userRepository.save(new User(username, "pw", Role.EMPLOYEE)));
        employee.approve(wage);
        return employeeRepository.save(employee);
    }

    private Shift shift(String name, int startHour, int endHour) {
        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(LocalTime.of(startHour, 0));
        shift.setEndTime(LocalTime.of(endHour, 0));
        shift.setRequiredStaff(1);
        shift.setStore(store);
        return shiftRepository.save(shift);
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }

    private static boolean commandSucceeds(String... command) {
        try {
            return new ProcessBuilder(command).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
