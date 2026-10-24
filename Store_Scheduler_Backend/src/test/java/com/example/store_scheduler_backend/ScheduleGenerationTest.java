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
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 CP-SAT 엔진을 실행해 근무표 생성 → 저장까지 검증한다.
 * python3 + ortools가 없는 환경에서는 건너뛴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScheduleGenerationTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 11, 2); // 월요일

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private Store store;
    private Employee weekdayStaff;
    private Employee midweekStaff;
    private Employee pendingStaff;

    @BeforeAll
    static void requireEngine() {
        assumeTrue(commandSucceeds("python3", "-c", "import ortools"), "python3/ortools가 없어 건너뜀");
    }

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(new User("owner", "pw", Role.MANAGER));
        store = storeRepository.save(new Store("테스트매장", null, owner, "GENCODE1"));

        weekdayStaff = approvedEmployee("월화수", 10030);
        midweekStaff = approvedEmployee("수목", 11000);
        pendingStaff = employeeRepository.save(new Employee("대기", null, store,
                userRepository.save(new User("pending", "pw", Role.EMPLOYEE))));

        available(weekdayStaff, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        available(midweekStaff, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
        available(pendingStaff, DayOfWeek.values());

        shift("오픈", LocalTime.of(9, 0), LocalTime.of(13, 0));
        shift("마감", LocalTime.of(22, 0), LocalTime.of(2, 0));
    }

    @Test
    void 근무표를_생성해_저장하고_가용_요일과_승인_직원만_배정한다() throws Exception {
        automate(WEEK_START)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.savedCount").value(greaterThan(0)))
                // 금~일은 가용 직원이 없으므로 최소 인원 미달이 보고되어야 한다
                .andExpect(jsonPath("$.shortages").value(not(empty())));

        List<Schedule> saved = scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId());
        assertThat(saved).isNotEmpty();
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.getWorkDate()).isBetween(WEEK_START, WEEK_START.plusDays(6));
            assertThat(s.getEmployee().getId()).isNotEqualTo(pendingStaff.getId());
        });
        assertThat(daysOf(saved, weekdayStaff)).isSubsetOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        assertThat(daysOf(saved, midweekStaff)).isSubsetOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
        // 근무 시간은 Shift 설정을 그대로 따른다 (자정 넘김 포함)
        assertThat(saved).allSatisfy(s -> assertThat(s.getStartTime()).isIn(LocalTime.of(9, 0), LocalTime.of(22, 0)));
    }

    @Test
    void 같은_주를_다시_생성하면_기존_근무표를_교체하고_다른_주는_건드리지_않는다() throws Exception {
        Schedule nextWeek = new Schedule();
        nextWeek.setStore(store);
        nextWeek.setEmployee(weekdayStaff);
        nextWeek.setWorkDate(WEEK_START.plusWeeks(1));
        nextWeek.setStartTime(LocalTime.of(9, 0));
        nextWeek.setEndTime(LocalTime.of(13, 0));
        scheduleRepository.save(nextWeek);

        automate(WEEK_START).andExpect(status().isOk());
        int firstCount = countInWeek();
        automate(WEEK_START).andExpect(status().isOk());

        assertThat(countInWeek()).isEqualTo(firstCount);
        assertThat(scheduleRepository.findById(nextWeek.getId())).isPresent();
    }

    @Test
    void 시작일이_월요일이_아니면_거절한다() throws Exception {
        automate(WEEK_START.plusDays(1)).andExpect(status().isBadRequest());
    }

    private ResultActions automate(LocalDate weekStart) throws Exception {
        return mockMvc.perform(post("/api/stores/{storeId}/schedules/automate", store.getId())
                .param("weekStart", weekStart.toString())
                .header("Authorization", "Bearer " + jwtTokenProvider.createToken("owner", "MANAGER")));
    }

    private int countInWeek() {
        return (int) scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId()).stream()
                .filter(s -> !s.getWorkDate().isAfter(WEEK_START.plusDays(6)))
                .count();
    }

    private Set<DayOfWeek> daysOf(List<Schedule> schedules, Employee employee) {
        return schedules.stream()
                .filter(s -> s.getEmployee().getId().equals(employee.getId()))
                .map(s -> s.getWorkDate().getDayOfWeek())
                .collect(java.util.stream.Collectors.toSet());
    }

    private Employee approvedEmployee(String name, int wage) {
        User user = userRepository.save(new User("user-" + name, "pw", Role.EMPLOYEE));
        Employee employee = new Employee(name, null, store, user);
        employee.approve(wage);
        return employeeRepository.save(employee);
    }

    private void available(Employee employee, DayOfWeek... days) {
        for (DayOfWeek day : days) {
            availabilityRepository.save(new Availability(employee, day, LocalTime.of(0, 0), LocalTime.of(23, 59)));
        }
    }

    private void shift(String name, LocalTime start, LocalTime end) {
        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(start);
        shift.setEndTime(end);
        shift.setRequiredStaff(1);
        shift.setStore(store);
        shiftRepository.save(shift);
    }

    private static boolean commandSucceeds(String... command) {
        try {
            return new ProcessBuilder(command).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
