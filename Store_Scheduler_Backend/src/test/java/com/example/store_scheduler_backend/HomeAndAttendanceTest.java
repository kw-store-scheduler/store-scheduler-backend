package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapOffer;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.SwapOfferRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HomeAndAttendanceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SwapRequestRepository swapRequestRepository;
    @Autowired SwapOfferRepository swapOfferRepository;

    private Store store;
    private Employee yuna;
    private Employee jisu;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("구시대 커피", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "HOMECD01"));
        shift("오픈", 9, 14, 2);
        shift("마감", 17, 22, 1);

        yuna = employee("yuna", "유나", 10_000);
        jisu = employee("jisu", "지수", 12_000);
        Employee pending = new Employee("대윤", null, store, userRepository.save(new User("daeyun", "pw", Role.EMPLOYEE)));
        employeeRepository.save(pending);

        schedule(yuna, TODAY, 9, 14);
        Schedule jisuOpen = schedule(jisu, TODAY, 9, 14);
        SwapRequest request = swapRequestRepository.save(new SwapRequest(jisuOpen, "건강 악화"));
        request.markOffered();
        swapOfferRepository.save(new SwapOffer(request, yuna, true));
    }

    @Test
    void 점주_홈에_이번_주_인건비와_오늘_교대별_인원_결원_승인_대기가_나온다() throws Exception {
        as("owner", get("/api/stores/{storeId}/home/owner", store.getId()).param("date", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyLaborCost").value(5 * 10_000 + 5 * 12_000))
                .andExpect(jsonPath("$.employeeCount").value(2))
                .andExpect(jsonPath("$.pendingApprovals").value(1))
                .andExpect(jsonPath("$.activeVacancies.length()").value(1))
                .andExpect(jsonPath("$.activeVacancies[0].requesterName").value("지수"))
                .andExpect(jsonPath("$.todayShifts[0].shiftName").value("오픈"))
                .andExpect(jsonPath("$.todayShifts[0].employeeNames.length()").value(2))
                .andExpect(jsonPath("$.todayShifts[0].shortage").value(0))
                .andExpect(jsonPath("$.todayShifts[1].shiftName").value("마감"))
                .andExpect(jsonPath("$.todayShifts[1].shortage").value(1));
    }

    @Test
    void 알바_홈에_오늘_근무와_같이_일하는_사람_나에게_온_대타_요청_예상_급여가_나온다() throws Exception {
        as("yuna", get("/api/stores/{storeId}/home/worker", store.getId()).param("date", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeName").value("유나"))
                .andExpect(jsonPath("$.today.shiftName").value("오픈"))
                .andExpect(jsonPath("$.today.startTime").value("09:00:00"))
                .andExpect(jsonPath("$.coworkers[0]").value("지수"))
                .andExpect(jsonPath("$.week.length()").value(7))
                .andExpect(jsonPath("$.swapOffers.length()").value(1))
                .andExpect(jsonPath("$.swapOffers[0].expectedPay").value(5 * 10_000))
                .andExpect(jsonPath("$.monthlyPay.totalHours").value(5.0));

        as("jisu", get("/api/stores/{storeId}/home/worker", store.getId()))
                .andExpect(jsonPath("$.swapOffers.length()").value(0));
        as("daeyun", get("/api/stores/{storeId}/home/worker", store.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 하루에_한_번_출근하고_퇴근하면_점주가_현황을_본다() throws Exception {
        as("yuna", post("/api/stores/{storeId}/attendance/clock-out", store.getId()))
                .andExpect(status().isBadRequest());
        as("yuna", post("/api/stores/{storeId}/attendance/clock-in", store.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledStart").value("09:00:00"))
                .andExpect(jsonPath("$.clockOut").doesNotExist());
        as("yuna", post("/api/stores/{storeId}/attendance/clock-in", store.getId()))
                .andExpect(status().isBadRequest());
        as("yuna", post("/api/stores/{storeId}/attendance/clock-out", store.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clockOut").exists());

        as("owner", get("/api/stores/{storeId}/attendance", store.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeName").value("유나"));
        as("owner", get("/api/stores/{storeId}/home/owner", store.getId()))
                .andExpect(jsonPath("$.todayAttendance[0].employeeName").value("유나"));
        as("yuna", get("/api/stores/{storeId}/attendance/my", store.getId())
                .param("from", TODAY.minusDays(7).toString()).param("to", TODAY.toString()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    private Employee employee(String username, String name, int wage) {
        Employee employee = new Employee(name, null, store, userRepository.save(new User(username, "pw", Role.EMPLOYEE)));
        employee.approve(wage);
        return employeeRepository.save(employee);
    }

    private void shift(String name, int startHour, int endHour, int requiredStaff) {
        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(LocalTime.of(startHour, 0));
        shift.setEndTime(LocalTime.of(endHour, 0));
        shift.setRequiredStaff(requiredStaff);
        shift.setStore(store);
        shiftRepository.save(shift);
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

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
