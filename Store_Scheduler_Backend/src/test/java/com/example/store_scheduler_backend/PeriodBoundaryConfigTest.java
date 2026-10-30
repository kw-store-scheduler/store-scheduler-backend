package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.service.ScheduleAutomationService;
import com.example.store_scheduler_backend.service.ScheduleEngine;
import com.example.store_scheduler_backend.service.SubstituteFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 앞뒤 기간과 이어지는 연속 근무·주 시간, 미성년자 주 35시간을 엔진과 대타 검사에 반영
@SpringBootTest
@Transactional
class PeriodBoundaryConfigTest {

    private static final LocalDate MONDAY = LocalDate.now().plusWeeks(3).with(DayOfWeek.MONDAY);

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleAutomationService automationService;
    @Autowired SubstituteFinder substituteFinder;
    @MockitoBean ScheduleEngine engine;

    private Store store;
    private Employee employee;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("경계", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "BOUNDARY"));
        employee = employee("worker", false);
        Shift shift = new Shift();
        shift.setName("오픈");
        shift.setStartTime(LocalTime.of(9, 0));
        shift.setEndTime(LocalTime.of(17, 0));
        shift.setRequiredStaff(1);
        shift.setStore(store);
        shiftRepository.save(shift);
        Map<String, Object> failed = new HashMap<>();
        failed.put("status", "INFEASIBLE");
        failed.put("schedule", null);
        when(engine.run(any())).thenReturn(failed);
    }

    @Test
    void 앞뒤_기간에_이어진_연속_근무일을_엔진에_넘긴다() {
        schedule(employee, MONDAY.minusDays(1));
        schedule(employee, MONDAY.minusDays(2));
        schedule(employee, MONDAY.minusDays(4));
        schedule(employee, MONDAY.plusDays(7));

        Map<String, Object> config = generateWeek(employee);

        assertThat(config.get("prior_run_days")).isEqualTo(2);
        assertThat(config.get("next_run_days")).isEqualTo(1);
        assertThat(config).doesNotContainKey("week_prior_hours");
    }

    @Test
    void 월을_걸친_주는_기간_밖_시간을_주_인덱스별로_넘긴다() {
        YearMonth month = YearMonth.from(MONDAY).plusMonths(1);
        LocalDate first = month.atDay(1);
        while (first.getDayOfWeek() == DayOfWeek.MONDAY) {
            month = month.plusMonths(1);
            first = month.atDay(1);
        }
        schedule(employee, first.minusDays(1));

        automationService.generateMonthlyProposal(store.getId(), month, "owner");

        assertThat((Map<String, Object>) employeeConfig(employee)).containsEntry("week_prior_hours", Map.of("0", 8));
    }

    @Test
    void 미성년자는_주_35시간으로_엔진에_넘기고_대타도_막는다() {
        Employee minor = employee("minor", true);
        for (int day : List.of(0, 1, 2, 4, 5)) {
            schedule(minor, MONDAY.plusDays(day)).setEndTime(LocalTime.of(16, 0));
        }

        assertThat(generateWeek(minor)).containsEntry("max_weekly_hours", 35);
        assertThat(substituteFinder.find(store.getId(), MONDAY.plusDays(6), LocalTime.of(9, 0), LocalTime.of(16, 0), null))
                .filteredOn(c -> c.employee().getId().equals(minor.getId()))
                .singleElement()
                .satisfies(c -> assertThat(c.reasons().get(0)).contains("35시간"));
    }

    private Map<String, Object> generateWeek(Employee target) {
        automationService.generateProposal(store.getId(), MONDAY, "owner");
        return employeeConfig(target);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> employeeConfig(Employee target) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(engine, atLeastOnce()).run(captor.capture());
        return ((List<Map<String, Object>>) captor.getValue().get("employees")).stream()
                .filter(e -> e.get("id").equals("emp_" + target.getId()))
                .findFirst().orElseThrow();
    }

    private Employee employee(String name, boolean minor) {
        Employee e = new Employee(name, null, store, userRepository.save(new User(name, "pw", Role.EMPLOYEE)));
        e.fillJoinProfile(null, null, minor, null);
        e.approve(10030);
        employeeRepository.save(e);
        for (DayOfWeek d : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(e, d, LocalTime.MIN, LocalTime.of(23, 59)));
        }
        return e;
    }

    private Schedule schedule(Employee target, LocalDate date) {
        Schedule s = new Schedule();
        s.setStore(store);
        s.setEmployee(target);
        s.setWorkDate(date);
        s.setStartTime(LocalTime.of(9, 0));
        s.setEndTime(LocalTime.of(17, 0));
        return scheduleRepository.save(s);
    }
}
