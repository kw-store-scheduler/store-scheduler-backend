package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 근무 넘기기·맞교환: 동료 수락 → 사장님 승인, 48시간 전 제한, 서로 바꾼 상태로 규칙 검사, 매주 반복
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShiftTradeTest {

    // 매주 반복이 같은 달 안에 들어가도록 다음 달 초를 기준으로 한다
    private static final LocalDate BASE = YearMonth.now().plusMonths(1).atDay(3);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private Store store;
    private Employee me;
    private Employee hajun;
    private Employee seoyeon;
    private Schedule myClose;
    private Schedule hajunOpen;
    private Schedule seoyeonSameDay;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("교대매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "TRADECD1"));
        me = employee("me", "나");
        hajun = employee("hajun", "하준");
        seoyeon = employee("seoyeon", "서연");
        myClose = schedule(me, BASE, 17, 22);
        hajunOpen = schedule(hajun, BASE.plusDays(3), 9, 14);
        seoyeonSameDay = schedule(seoyeon, BASE, 9, 14);
    }

    @Test
    void 같은_날_근무가_있는_동료도_서로_바꾸면_규칙을_지키므로_맞교환_선택지에_나온다() throws Exception {
        JsonNode options = body(as("me", get("/api/stores/{s}/schedules/{id}/trade-options", store.getId(), myClose.getId())));

        JsonNode seoyeonHandover = find(options.get("handover"), "employeeId", seoyeon.getId());
        assertThat(seoyeonHandover.get("available").asBoolean()).isFalse();
        assertThat(seoyeonHandover.get("blocker").asText()).contains("같은 날");
        assertThat(find(options.get("exchange"), "scheduleId", seoyeonSameDay.getId()).get("available").asBoolean()).isTrue();
        assertThat(find(options.get("exchange"), "scheduleId", hajunOpen.getId()).get("available").asBoolean()).isTrue();
    }

    @Test
    void 맞교환은_동료가_수락하고_사장님이_승인하면_근무표가_바뀐다() throws Exception {
        long tradeId = body(as("me", post("/api/stores/{s}/schedules/{id}/trades", store.getId(), myClose.getId())
                .contentType("application/json")
                .content("{\"type\":\"EXCHANGE\",\"targetScheduleId\":" + hajunOpen.getId() + "}"))).get("id").asLong();

        as("owner", post("/api/stores/{s}/trades/{t}/approve", store.getId(), tradeId)).andExpect(status().isBadRequest());
        as("seoyeon", post("/api/stores/{s}/trades/{t}/accept", store.getId(), tradeId)).andExpect(status().isForbidden());
        as("hajun", post("/api/stores/{s}/trades/{t}/accept", store.getId(), tradeId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PEER_ACCEPTED"));
        as("owner", get("/api/stores/{s}/trades", store.getId())).andExpect(jsonPath("$.length()").value(1));
        as("owner", post("/api/stores/{s}/trades/{t}/approve", store.getId(), tradeId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(scheduleRepository.findById(myClose.getId()).orElseThrow().getEmployee().getId()).isEqualTo(hajun.getId());
        assertThat(scheduleRepository.findById(hajunOpen.getId()).orElseThrow().getEmployee().getId()).isEqualTo(me.getId());
    }

    @Test
    void 넘기기를_매주_반복하면_그달_남은_같은_요일_근무도_넘어간다() throws Exception {
        Schedule nextWeek = schedule(me, BASE.plusWeeks(1), 17, 22);
        long tradeId = body(as("me", post("/api/stores/{s}/schedules/{id}/trades", store.getId(), myClose.getId())
                .contentType("application/json")
                .content("{\"type\":\"HANDOVER\",\"targetEmployeeId\":" + hajun.getId() + ",\"recurring\":true}"))).get("id").asLong();
        as("hajun", post("/api/stores/{s}/trades/{t}/accept", store.getId(), tradeId)).andExpect(status().isOk());
        as("owner", post("/api/stores/{s}/trades/{t}/approve", store.getId(), tradeId)).andExpect(status().isOk());

        assertThat(scheduleRepository.findById(myClose.getId()).orElseThrow().getEmployee().getId()).isEqualTo(hajun.getId());
        assertThat(scheduleRepository.findById(nextWeek.getId()).orElseThrow().getEmployee().getId()).isEqualTo(hajun.getId());
    }

    @Test
    void 근무_48시간_전이_지나면_바꿀_수_없고_같은_날_근무가_있는_동료에게는_넘길_수_없다() throws Exception {
        Schedule tomorrow = schedule(me, LocalDate.now().plusDays(1), 9, 14);
        as("me", post("/api/stores/{s}/schedules/{id}/trades", store.getId(), tomorrow.getId())
                .contentType("application/json")
                .content("{\"type\":\"HANDOVER\",\"targetEmployeeId\":" + hajun.getId() + "}"))
                .andExpect(status().isBadRequest());
        as("me", post("/api/stores/{s}/schedules/{id}/trades", store.getId(), myClose.getId())
                .contentType("application/json")
                .content("{\"type\":\"HANDOVER\",\"targetEmployeeId\":" + seoyeon.getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode find(JsonNode list, String field, long id) {
        for (JsonNode n : list) {
            if (n.get(field).asLong() == id) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + id + " 없음: " + list);
    }

    private Employee employee(String username, String name) {
        Employee employee = new Employee(name, null, store, userRepository.save(new User(username, "pw", Role.EMPLOYEE)));
        employee.approve(10_000);
        employeeRepository.save(employee);
        for (DayOfWeek day : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(employee, day, LocalTime.of(0, 0), LocalTime.of(23, 59)));
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
