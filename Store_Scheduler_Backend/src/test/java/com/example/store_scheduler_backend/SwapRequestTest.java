package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapStatus;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
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
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 결원 요청 → 대타 추천 → 지정 → 수락/거절 흐름
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SwapRequestTest {

    // 다음 주 수요일 오픈(09~13) 근무를 기준으로 한다
    private static final LocalDate MONDAY = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    private static final LocalDate TARGET = MONDAY.plusDays(2);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SwapRequestRepository swapRequestRepository;

    private Store store;
    private Employee requester;
    private Employee free;        // 가능: 이번 주 근무 없음
    private Employee busy;        // 가능: 이번 주 4시간 근무 중 → free보다 뒤
    private Employee sameDay;     // 불가: 같은 날 근무
    private Employee lateOnly;    // 불가: 가용 시간 밖
    private Employee streak;      // 불가: 연속 5일이 됨
    private Schedule target;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(new User("owner", "pw", Role.MANAGER));
        store = storeRepository.save(new Store("테스트매장", null, owner, "SWAPCODE"));

        requester = employee("요청자", 10030);
        free = employee("여유", 10030);
        busy = employee("바쁨", 10030);
        sameDay = employee("같은날", 10030);
        lateOnly = employee("저녁만", 10030);
        streak = employee("연속", 10030);
        employeeRepository.save(new Employee("대기", null, store, userRepository.save(new User("pending", "pw", Role.EMPLOYEE))));

        for (Employee e : new Employee[]{requester, free, busy, sameDay, streak}) {
            available(e, LocalTime.of(9, 0), LocalTime.of(18, 0));
        }
        available(lateOnly, LocalTime.of(17, 0), LocalTime.of(23, 59));

        target = schedule(requester, TARGET, 9, 13);
        schedule(busy, MONDAY, 9, 13);
        schedule(sameDay, TARGET, 14, 18);
        for (int i = 1; i <= 4; i++) {
            schedule(streak, TARGET.minusDays(i), 9, 13);
        }
    }

    @Test
    void 대타_추천은_가능한_직원을_근무시간_적은_순으로_보여주고_불가능한_직원은_사유를_붙인다() throws Exception {
        long requestId = requestSwap();

        JsonNode candidates = read(as("owner", Role.MANAGER, get(candidatesUrl(requestId))).andExpect(status().isOk()));

        assertThat(candidates.size()).isEqualTo(5);  // 요청자·승인 대기 직원 제외
        assertThat(candidates.get(0).get("name").asText()).isEqualTo("여유");
        assertThat(candidates.get(1).get("name").asText()).isEqualTo("바쁨");
        assertThat(candidates.get(0).get("eligible").asBoolean()).isTrue();
        assertThat(candidates.get(1).get("eligible").asBoolean()).isTrue();
        assertThat(reasonOf(candidates, "같은날")).isEqualTo("같은 날 다른 근무가 있음");
        assertThat(reasonOf(candidates, "저녁만")).contains("가용 시간");
        assertThat(reasonOf(candidates, "연속")).isEqualTo("연속 5일 근무가 됨");
    }

    @Test
    void 대타가_수락하면_근무_담당자가_바뀐다() throws Exception {
        long requestId = requestSwap();
        offer(requestId, free).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFERED"));

        as("user-여유", Role.EMPLOYEE, post(actionUrl(requestId, "accept")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(scheduleRepository.findById(target.getId()).orElseThrow().getEmployee().getId()).isEqualTo(free.getId());
    }

    @Test
    void 대타가_거절하면_다시_대타를_찾는_상태가_된다() throws Exception {
        long requestId = requestSwap();
        offer(requestId, free);

        as("user-여유", Role.EMPLOYEE, post(actionUrl(requestId, "decline")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.substituteId").isEmpty());
        assertThat(scheduleRepository.findById(target.getId()).orElseThrow().getEmployee().getId()).isEqualTo(requester.getId());
    }

    @Test
    void 근무할_수_없는_직원은_대타로_지정할_수_없다() throws Exception {
        long requestId = requestSwap();
        offer(requestId, sameDay).andExpect(status().isBadRequest());
        offer(requestId, streak).andExpect(status().isBadRequest());
    }

    @Test
    void 지정받지_않은_직원은_수락할_수_없다() throws Exception {
        long requestId = requestSwap();
        offer(requestId, free);
        as("user-바쁨", Role.EMPLOYEE, post(actionUrl(requestId, "accept"))).andExpect(status().isForbidden());
    }

    @Test
    void 다른_직원의_근무에는_결원_요청을_할_수_없고_같은_근무에_중복_요청도_안_된다() throws Exception {
        as("user-여유", Role.EMPLOYEE, post(requestUrl())).andExpect(status().isForbidden());
        requestSwap();
        as("user-요청자", Role.EMPLOYEE, post(requestUrl())).andExpect(status().isBadRequest());
    }

    @Test
    void 관리자가_근무를_삭제하면_진행_중인_결원_요청은_취소된다() throws Exception {
        long requestId = requestSwap();

        as("owner", Role.MANAGER, delete("/api/stores/{storeId}/schedules/{id}", store.getId(), target.getId()))
                .andExpect(status().isNoContent());

        var request = swapRequestRepository.findById(requestId).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(SwapStatus.CANCELLED);
        assertThat(request.getSchedule()).isNull();
        assertThat(request.getWorkDate()).isEqualTo(TARGET);
    }

    @Test
    void 요청한_직원은_대타_확정_전에_취소할_수_있다() throws Exception {
        long requestId = requestSwap();
        as("user-요청자", Role.EMPLOYEE, post(actionUrl(requestId, "cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        offer(requestId, free).andExpect(status().isBadRequest());
    }

    private long requestSwap() throws Exception {
        JsonNode body = read(as("user-요청자", Role.EMPLOYEE, post(requestUrl())
                .contentType("application/json").content("{\"reason\":\"병원 진료\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED")));
        return body.get("id").asLong();
    }

    private ResultActions offer(long requestId, Employee substitute) throws Exception {
        return as("owner", Role.MANAGER, post(actionUrl(requestId, "offer"))
                .contentType("application/json").content("{\"employeeId\":" + substitute.getId() + "}"));
    }

    private ResultActions as(String username, Role role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, role.name())));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String reasonOf(JsonNode candidates, String name) {
        for (JsonNode c : candidates) {
            if (c.get("name").asText().equals(name)) {
                assertThat(c.get("eligible").asBoolean()).isFalse();
                return c.get("reasons").get(0).asText();
            }
        }
        throw new AssertionError("후보 목록에 없음: " + name);
    }

    private String requestUrl() {
        return "/api/stores/" + store.getId() + "/schedules/" + target.getId() + "/swap-requests";
    }

    private String candidatesUrl(long requestId) {
        return "/api/stores/" + store.getId() + "/swap-requests/" + requestId + "/candidates";
    }

    private String actionUrl(long requestId, String action) {
        return "/api/stores/" + store.getId() + "/swap-requests/" + requestId + "/" + action;
    }

    private Employee employee(String name, int wage) {
        Employee employee = new Employee(name, null, store, userRepository.save(new User("user-" + name, "pw", Role.EMPLOYEE)));
        employee.approve(wage);
        return employeeRepository.save(employee);
    }

    private void available(Employee employee, LocalTime from, LocalTime to) {
        for (DayOfWeek day : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(employee, day, from, to));
        }
    }

    private Schedule schedule(Employee employee, LocalDate date, int startHour, int endHour) {
        Schedule schedule = new Schedule();
        schedule.setStore(store);
        schedule.setEmployee(employee);
        schedule.setWorkDate(date);
        schedule.setStartTime(LocalTime.of(startHour, 0));
        schedule.setEndTime(LocalTime.of(endHour, 0));
        return scheduleRepository.save(schedule);
    }
}
