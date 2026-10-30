package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import com.example.store_scheduler_backend.service.ShiftTradeService;
import com.example.store_scheduler_backend.service.SwapRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 점주의 근무 직접 추가·담당자 변경: 근무 규칙 확인, force, 진행 중인 요청과의 충돌, 알림
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScheduleManualEditTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(10);

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired SwapRequestService swapRequestService;
    @Autowired ShiftTradeService shiftTradeService;

    private Store store;
    private Employee yuna;
    private Employee minho;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("수정매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "MANUAL01"));
        yuna = employee("yuna");
        minho = employee("minho");
    }

    @Test
    void 점주가_근무를_추가하면_직원에게_알림이_간다() throws Exception {
        create(yuna, DAY, false).andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value(yuna.getId()));

        assertThat(scheduleRepository.findByStoreIdAndWorkDateBetween(store.getId(), DAY, DAY)).hasSize(1);
        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("yuna"))
                .singleElement().satisfies(n -> assertThat(n.getTitle()).isEqualTo("근무 추가"));
    }

    @Test
    void 필수_휴무일_배정은_사유와_함께_거부되고_force면_배정된다() throws Exception {
        dayOffRequestRepository.save(new DayOffRequest(yuna, DAY, DayOffType.MUST, "병원"));

        create(yuna, DAY, false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("필수 휴무")));
        create(yuna, DAY, true).andExpect(status().isOk());
    }

    @Test
    void 퇴사한_직원은_force여도_배정할_수_없다() throws Exception {
        yuna.resign(LocalDate.now());

        create(yuna, DAY, true).andExpect(status().isBadRequest());
    }

    @Test
    void 담당자를_바꾸면_양쪽에_알리고_새_담당자의_규칙을_확인한다() throws Exception {
        Schedule shift = schedule(yuna, DAY);

        update(shift, minho.getId(), false).andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value(minho.getId()));

        assertThat(shift.getEmployee().getId()).isEqualTo(minho.getId());
        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("yuna")).hasSize(1);
        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("minho")).hasSize(1);

        Schedule other = schedule(yuna, DAY);
        update(other, minho.getId(), false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("같은 날")));
    }

    @Test
    void 시간만_바꿀_때_자기_근무와_겹친다고_거부하지_않는다() throws Exception {
        Schedule shift = schedule(yuna, DAY);

        update(shift, null, false).andExpect(status().isOk());
    }

    @Test
    void 결원이나_근무_바꾸기가_진행_중인_근무는_수정할_수_없다() throws Exception {
        Schedule vacant = schedule(yuna, DAY);
        swapRequestService.request(store.getId(), vacant.getId(), "사정", "yuna");
        update(vacant, minho.getId(), true).andExpect(status().isBadRequest());

        Schedule traded = schedule(minho, DAY.plusDays(1));
        shiftTradeService.request(store.getId(), traded.getId(), TradeType.HANDOVER, yuna.getId(), null, false, "minho");
        update(traded, null, true).andExpect(status().isBadRequest());
    }

    @Test
    void 근무를_삭제하면_직원에게_알린다() throws Exception {
        Schedule shift = schedule(yuna, DAY);

        as(delete("/api/stores/{s}/schedules/{id}", store.getId(), shift.getId())).andExpect(status().isNoContent());

        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("yuna"))
                .singleElement().satisfies(n -> assertThat(n.getTitle()).isEqualTo("근무 취소"));
    }

    @Test
    void 지난_날짜는_기록_수정이라_규칙을_묻지_않고_알림도_보내지_않는다() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        dayOffRequestRepository.save(new DayOffRequest(yuna, yesterday, DayOffType.MUST, null));

        create(yuna, yesterday, false).andExpect(status().isOk());

        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("yuna")).isEmpty();
    }

    private ResultActions create(Employee employee, LocalDate date, boolean force) throws Exception {
        return as(post("/api/stores/{s}/schedules", store.getId()).contentType("application/json")
                .content("{\"employeeId\":" + employee.getId() + ",\"workDate\":\"" + date
                        + "\",\"startTime\":\"09:00\",\"endTime\":\"13:00\",\"force\":" + force + "}"));
    }

    private ResultActions update(Schedule schedule, Long employeeId, boolean force) throws Exception {
        return as(put("/api/stores/{s}/schedules/{id}", store.getId(), schedule.getId()).contentType("application/json")
                .content("{\"employeeId\":" + employeeId + ",\"workDate\":\"" + schedule.getWorkDate()
                        + "\",\"startTime\":\"10:00\",\"endTime\":\"14:00\",\"force\":" + force + "}"));
    }

    private ResultActions as(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken("owner", "MANAGER")));
    }

    private Employee employee(String name) {
        Employee e = new Employee(name, null, store, userRepository.save(new User(name, "pw", Role.EMPLOYEE)));
        e.approve(10030);
        employeeRepository.save(e);
        for (DayOfWeek d : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(e, d, LocalTime.MIN, LocalTime.of(23, 59)));
        }
        return e;
    }

    private Schedule schedule(Employee employee, LocalDate date) {
        Schedule s = new Schedule();
        s.setStore(store);
        s.setEmployee(employee);
        s.setWorkDate(date);
        s.setStartTime(LocalTime.of(9, 0));
        s.setEndTime(LocalTime.of(13, 0));
        return scheduleRepository.save(s);
    }
}
