package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.service.EmployeeService;
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

// 휴게시간, 하루 8시간 초과 연장, 공휴일 가산, 시급 변경 이력
@SpringBootTest
@Transactional
class PayrollLaborRulesTest {

    // 2026-11-16 월요일, 공휴일 없는 주
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired PayrollService payrollService;
    @Autowired EmployeeService employeeService;

    private Store store;
    private Employee employee;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("급여", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "PAYRULE1"));
        employee = new Employee("직원", null, store, userRepository.save(new User("worker", "pw", Role.EMPLOYEE)));
        employee.approve(10000);
        employeeRepository.save(employee);
        store.updateOperations(null, true, null, null, null, null);
    }

    @Test
    void 휴게시간_설정을_켜면_법정_휴게시간을_뺀다() {
        schedule(MONDAY, 9, 18);
        schedule(MONDAY.plusDays(1), 9, 14);
        schedule(MONDAY.plusDays(2), 9, 12);
        assertThat(november().getTotalHours()).isEqualTo(17.0);

        store.updateBreakDeduction(true);

        assertThat(november().getTotalHours()).isEqualTo(15.5);
    }

    @Test
    void 주_40시간_이내여도_하루_8시간을_넘으면_연장_가산을_준다() {
        schedule(MONDAY, 9, 20);

        assertThat(november().getOvertimePay()).isEqualTo(15_000);
    }

    @Test
    void 하루_초과와_주_초과는_겹치지_않게_큰_쪽만_준다() {
        for (int i = 0; i < 5; i++) {
            schedule(MONDAY.plusDays(i), 9, 18);
        }

        assertThat(november().getOvertimePay()).isEqualTo(25_000);
    }

    @Test
    void 공휴일_근무는_8시간까지_50퍼센트_초과분_100퍼센트_가산한다() {
        schedule(LocalDate.of(2026, 12, 25), 9, 19);

        var december = payrollService.calculateForEmployee(employee, YearMonth.of(2026, 12));

        assertThat(december.getHolidayPay()).isEqualTo(40_000 + 20_000);
        assertThat(december.getOvertimePay()).isZero();
        assertThat(december.getTotalPay()).isEqualTo(100_000 + 60_000);
    }

    @Test
    void 상시_5인_미만은_연장과_공휴일_가산이_없다() {
        store.updateOperations(null, false, null, null, null, null);
        schedule(LocalDate.of(2026, 12, 25), 9, 19);

        var december = payrollService.calculateForEmployee(employee, YearMonth.of(2026, 12));

        assertThat(december.getHolidayPay()).isZero();
        assertThat(december.getOvertimePay()).isZero();
    }

    @Test
    void 시급을_올려도_변경일_이전_근무는_이전_시급으로_계산한다() {
        LocalDate before = LocalDate.now().minusDays(10);
        LocalDate after = LocalDate.now().plusDays(10);
        schedule(before, 9, 13);
        schedule(after, 9, 13);

        employeeService.updateEmployee(store.getId(), employee.getId(), null, null, 12000, null, "owner");

        assertThat(payrollService.calculateForEmployee(employee, YearMonth.from(before)).getRegularWage()
                + (YearMonth.from(before).equals(YearMonth.from(after)) ? 0
                : payrollService.calculateForEmployee(employee, YearMonth.from(after)).getRegularWage()))
                .isEqualTo(40_000 + 48_000);
    }

    private PayrollService.EmployeePayroll november() {
        return payrollService.calculateForEmployee(employee, NOVEMBER);
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
