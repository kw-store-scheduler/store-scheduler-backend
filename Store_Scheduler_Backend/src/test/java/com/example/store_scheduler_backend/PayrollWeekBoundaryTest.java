package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.service.PayrollService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

// 2026-09-28(월)~10-04(일)처럼 월을 걸친 주의 주휴수당·연장 가산
@SpringBootTest
@Transactional
class PayrollWeekBoundaryTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired PayrollService payrollService;

    private Store store;
    private Employee employee;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("급여매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "PAYWEEK1"));
        employee = new Employee("직원", null, store, userRepository.save(new User("worker", "pw", Role.EMPLOYEE)));
        employee.approve(10000);
        employeeRepository.save(employee);
    }

    @Test
    void 월을_걸친_주의_주휴수당은_주_전체로_판단해_주가_끝나는_달에_한_번_준다() {
        for (int i = 0; i < 5; i++) {
            schedule(MONDAY.plusDays(i), 9, 13);
        }

        var september = payrollService.calculateForEmployee(employee, YearMonth.of(2026, 9));
        var october = payrollService.calculateForEmployee(employee, YearMonth.of(2026, 10));

        assertThat(september.getRegularWage()).isEqualTo(120_000);
        assertThat(september.getWeeklyHolidayPay()).isZero();
        assertThat(october.getRegularWage()).isEqualTo(80_000);
        assertThat(october.getWeeklyHolidayPay()).isEqualTo(40_000);
    }

    @Test
    void 월을_걸친_주의_연장_가산도_주_전체_시간으로_한_번만_계산한다() {
        store.updateOperations(null, true, null, null, null, null);
        for (int i = 0; i < 5; i++) {
            schedule(MONDAY.plusDays(i), 9, 18);
        }

        var september = payrollService.calculateForEmployee(employee, YearMonth.of(2026, 9));
        var october = payrollService.calculateForEmployee(employee, YearMonth.of(2026, 10));

        assertThat(september.getOvertimePay()).isZero();
        assertThat(october.getOvertimePay()).isEqualTo(25_000);
    }

    private void schedule(LocalDate date, int start, int end) {
        Schedule s = new Schedule();
        s.setStore(store);
        s.setEmployee(employee);
        s.setWorkDate(date);
        s.setStartTime(LocalTime.of(start, 0));
        s.setEndTime(LocalTime.of(end, 0));
        scheduleRepository.save(s);
    }
}
