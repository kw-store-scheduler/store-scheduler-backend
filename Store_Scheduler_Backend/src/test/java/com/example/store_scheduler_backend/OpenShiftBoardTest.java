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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 결원 신고 → 게시판 자동 게시 → 직원 지원 → 점주 승인, 추가 인원 모집
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenShiftBoardTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

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
    private Employee helper;
    private Schedule requesterShift;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("게시판매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "BOARDCD1"));
        requester = employee("inho", "인호", true);
        helper = employee("yuna", "유나", true);
        employee("busy", "가용없음", false);
        employee("third", "지수", true);
        requesterShift = schedule(requester, TOMORROW, 17, 22);
    }

    @Test
    void 결원을_신고하면_게시판에_올라가고_지원자를_승인하면_근무가_넘어간다() throws Exception {
        long requestId = reportAbsence();

        JsonNode board = body(as("yuna", get("/api/stores/{storeId}/open-shifts", store.getId())));
        assertThat(board.get("items").size()).isEqualTo(1);
        JsonNode item = board.get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("VACANCY");
        assertThat(item.get("eligible").asBoolean()).isTrue();
        assertThat(item.get("expectedPay").asLong()).isEqualTo(5 * 10_000);
        long openShiftId = item.get("id").asLong();

        JsonNode busyItem = body(as("busy", get("/api/stores/{storeId}/open-shifts", store.getId()))).get("items").get(0);
        assertThat(busyItem.get("eligible").asBoolean()).isFalse();
        assertThat(busyItem.get("blocker").asText()).contains("가용 시간");
        assertThat(body(as("inho", get("/api/stores/{storeId}/open-shifts", store.getId()))).get("items").size())
                .as("본인 결원은 보이지 않는다").isZero();

        as("busy", post("/api/stores/{storeId}/open-shifts/{id}/applications", store.getId(), openShiftId))
                .andExpect(status().isBadRequest());
        long applicationId = body(as("yuna", post("/api/stores/{storeId}/open-shifts/{id}/applications", store.getId(), openShiftId)))
                .get("id").asLong();
        as("yuna", post("/api/stores/{storeId}/open-shifts/{id}/applications", store.getId(), openShiftId))
                .andExpect(status().isBadRequest());

        JsonNode candidates = body(as("owner", get("/api/stores/{storeId}/swap-requests/{id}/candidates", store.getId(), requestId)));
        for (JsonNode c : candidates) {
            if (c.get("employeeId").asLong() == helper.getId()) {
                assertThat(c.get("contactStatus").asText()).isEqualTo("APPLIED");
            }
        }

        as("owner", post("/api/stores/{storeId}/open-shifts/{id}/applications/{aid}/approve", store.getId(), openShiftId, applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(scheduleRepository.findById(requesterShift.getId()).orElseThrow().getEmployee().getId()).isEqualTo(helper.getId());
        assertThat(swapRequestRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(SwapStatus.ACCEPTED);
        assertThat(body(as("owner", get("/api/stores/{storeId}/open-shifts", store.getId()))).get("items").size()).isZero();
    }

    @Test
    void 결원_요청을_취소하면_게시판_글도_내려가고_일괄_요청은_후보에게_다시_알린다() throws Exception {
        long requestId = reportAbsence();

        as("owner", post("/api/stores/{storeId}/swap-requests/{id}/broadcast", store.getId(), requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifiedCount").value(2));

        as("inho", post("/api/stores/{storeId}/swap-requests/{id}/cancel", store.getId(), requestId))
                .andExpect(status().isOk());
        assertThat(body(as("yuna", get("/api/stores/{storeId}/open-shifts", store.getId()))).get("items").size()).isZero();
    }

    @Test
    void 추가_모집은_인원이_차면_마감되고_나머지_지원은_거절된다() throws Exception {
        JsonNode created = body(as("owner", post("/api/stores/{storeId}/open-shifts", store.getId())
                .contentType("application/json")
                .content("{\"workDate\":\"" + TOMORROW + "\",\"startTime\":\"12:00\",\"endTime\":\"14:00\","
                        + "\"title\":\"단체 손님\",\"note\":\"예약 20명\"}")));
        assertThat(created.get("kind").asText()).isEqualTo("EXTRA");
        long openShiftId = created.get("id").asLong();

        long first = body(as("yuna", post("/api/stores/{storeId}/open-shifts/{id}/applications", store.getId(), openShiftId))).get("id").asLong();
        as("third", post("/api/stores/{storeId}/open-shifts/{id}/applications", store.getId(), openShiftId)).andExpect(status().isOk());
        as("owner", get("/api/stores/{storeId}/open-shifts/{id}/applications", store.getId(), openShiftId))
                .andExpect(jsonPath("$.length()").value(2));

        as("owner", post("/api/stores/{storeId}/open-shifts/{id}/applications/{aid}/approve", store.getId(), openShiftId, first))
                .andExpect(status().isOk());

        assertThat(scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(helper.getId(), TOMORROW, TOMORROW))
                .singleElement().satisfies(s -> assertThat(s.getStartTime()).isEqualTo(LocalTime.of(12, 0)));
        as("owner", get("/api/stores/{storeId}/open-shifts/{id}/applications", store.getId(), openShiftId))
                .andExpect(jsonPath("$[1].status").value("REJECTED"));
    }

    private long reportAbsence() throws Exception {
        return body(as("inho", post("/api/stores/{storeId}/schedules/{scheduleId}/swap-requests", store.getId(), requesterShift.getId())
                .contentType("application/json").content("{\"reason\":\"건강 악화\"}"))).get("id").asLong();
    }

    private Employee employee(String username, String name, boolean available) {
        Employee employee = new Employee(name, null, store, userRepository.save(new User(username, "pw", Role.EMPLOYEE)));
        employee.approve(10_000);
        employeeRepository.save(employee);
        if (available) {
            for (DayOfWeek day : DayOfWeek.values()) {
                availabilityRepository.save(new Availability(employee, day, LocalTime.of(0, 0), LocalTime.of(23, 59)));
            }
        }
        return employee;
    }

    private Schedule schedule(Employee employee, LocalDate date, int startHour, int endHour) {
        Schedule schedule = new Schedule();
        schedule.setEmployee(employee);
        schedule.setStore(store);
        schedule.setWorkDate(date);
        schedule.setStartTime(LocalTime.of(startHour, 0));
        schedule.setEndTime(LocalTime.of(endHour, 0));
        return scheduleRepository.save(schedule);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
