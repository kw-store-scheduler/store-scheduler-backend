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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PayrollProbationAndDeductionTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 2);

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
        store = storeRepository.save(new Store("수습", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "PROBATE1"));
        employee = new Employee("직원", null, store, userRepository.save(new User("worker", "pw", Role.EMPLOYEE)));
        employee.fillJoinProfile(null, START, false, null);
        employee.approve(11000);
        employee.updateProbation(START.plusYears(1));
        employeeRepository.save(employee);
    }

    @Test
    void 요건을_못_채우거나_하한_아래면_수습_시급을_정할_수_없다() {
        assertThatThrownBy(() -> settings(null, 10000, false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(null, 10000, true, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(null, 9000, true, false)).hasMessageContaining("90%");
    }

    @Test
    void 수습_시급은_수습_기간이_길어도_시작_3개월까지만_적용한다() {
        settings(null, 10000, true, false);
        schedule(START.plusMonths(2));
        schedule(START.plusMonths(4));

        assertThat(regularWage(START.plusMonths(2))).isEqualTo(40_000);
        assertThat(regularWage(START.plusMonths(4))).isEqualTo(44_000);
    }

    @Test
    void 해가_바뀌어_하한이_오르면_수습_시급도_그_아래로_계산하지_않는다() {
        settings(null, 9288, true, false);
        schedule(LocalDate.of(2027, 1, 5));

        assertThat(regularWage(LocalDate.of(2027, 1, 5))).isEqualTo(4 * 9630);
    }

    @Test
    void 프리랜서는_3점3퍼센트를_공제한다() {
        settings(PayDeduction.FREELANCE, null, false, false);
        schedule(START);

        var pay = payrollService.calculateForEmployee(employee, YearMonth.from(START));

        assertThat(pay.getTotalPay()).isEqualTo(44_000);
        assertThat(pay.getDeduction()).isEqualTo(1_450);
        assertThat(pay.getNetPay()).isEqualTo(42_550);
    }

    @Test
    void 사대보험은_근로자_부담분을_공제하고_공제_설정이_없으면_그대로다() {
        schedule(START);
        assertThat(payrollService.calculateForEmployee(employee, YearMonth.from(START)).getNetPay()).isEqualTo(44_000);

        settings(PayDeduction.FOUR_INSURANCE, null, false, false);

        // 44,000원 × (4.75% + 3.595% × 1.1314 + 0.9%) = 4,275원
        assertThat(payrollService.calculateForEmployee(employee, YearMonth.from(START)).getDeduction()).isEqualTo(4_270);
    }

    private void settings(PayDeduction deduction, Integer wage, boolean oneYear, boolean simple) {
        employeeService.updatePaySettings(store.getId(), employee.getId(), deduction, wage, oneYear, simple, "owner");
    }

    private long regularWage(LocalDate date) {
        return payrollService.calculateForEmployee(employee, YearMonth.from(date)).getRegularWage();
    }

    private void schedule(LocalDate date) {
        Schedule s = new Schedule();
        s.setStore(store);
        s.setEmployee(employee);
        s.setWorkDate(date);
        s.setStartTime(LocalTime.of(9, 0));
        s.setEndTime(LocalTime.of(13, 0));
        scheduleRepository.save(s);
    }
}
