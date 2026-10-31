package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.service.AttendanceService;
import com.example.store_scheduler_backend.service.SchedulePlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AttendanceCorrectionTest {

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AttendanceService attendanceService;

    private Store store;
    private Employee employee;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("출퇴근", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "ATTEND01"));
        employee = new Employee("직원", null, store, userRepository.save(new User("worker", "pw", Role.EMPLOYEE)));
        employee.approve(10030);
        employeeRepository.save(employee);
    }

    @Test
    void 하루가_넘은_출근_기록은_퇴근으로_닫지_않고_점주가_고친다() {
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        Attendance forgotten = attendanceRepository.save(new Attendance(employee, threeDaysAgo));

        assertThatThrownBy(() -> attendanceService.clockOut(store.getId(), "worker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("사장님");

        attendanceService.correct(store.getId(), forgotten.getId(), threeDaysAgo, threeDaysAgo.plusHours(5), "owner");
        assertThat(forgotten.getClockOut()).isEqualTo(threeDaysAgo.plusHours(5));
    }

    @Test
    void 자정을_넘긴_근무의_퇴근은_그대로_된다() {
        attendanceRepository.save(new Attendance(employee, LocalDateTime.now().minusHours(10)));

        assertThat(attendanceService.clockOut(store.getId(), "worker").attendance().getClockOut()).isNotNull();
    }

    @Test
    void 퇴근_시각이_출근보다_빠르면_거부한다() {
        LocalDateTime in = LocalDateTime.now().minusHours(2);
        Attendance record = attendanceRepository.save(new Attendance(employee, in));

        assertThatThrownBy(() -> attendanceService.correct(store.getId(), record.getId(), in, in.minusHours(1), "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이주_주기의_다음_기간은_매주_달라지지_않고_겹치지_않는다() {
        store.updateOperations(null, null, ScheduleCycle.BIWEEKLY, true, null, null);
        LocalDate day = LocalDate.of(2026, 11, 2);
        var first = SchedulePlanService.planOf(store, day);
        var nextWeek = SchedulePlanService.planOf(store, day.plusWeeks(1));
        var later = SchedulePlanService.planOf(store, first.periodStart());

        assertThat(nextWeek.periodStart()).isEqualTo(first.periodStart());
        assertThat(later.periodStart()).isEqualTo(first.periodEnd().plusDays(1));
    }

    @Test
    void 점주가_출근을_찍지_않은_날의_기록을_만든다() {
        LocalDateTime clockIn = LocalDate.now().minusDays(2).atTime(9, 0);

        AttendanceService.AttendanceView view = attendanceService.create(store.getId(), employee.getId(), clockIn, clockIn.plusHours(5), "owner");

        assertThat(view.attendance().getWorkDate()).isEqualTo(clockIn.toLocalDate());
        assertThat(view.attendance().getClockOut()).isEqualTo(clockIn.plusHours(5));
        assertThatThrownBy(() -> attendanceService.create(store.getId(), employee.getId(), clockIn, clockIn.plusHours(5), "owner"))
                .hasMessageContaining("이미 그날");
        assertThatThrownBy(() -> attendanceService.create(store.getId(), employee.getId(), LocalDateTime.now().plusDays(1), null, "owner"))
                .hasMessageContaining("앞으로의");
        assertThatThrownBy(() -> attendanceService.create(store.getId(), employee.getId(), clockIn.plusDays(1), clockIn.plusDays(1), "owner"))
                .hasMessageContaining("퇴근 시각");
    }

    @Test
    void 근무가_있었는데_출근_기록이_없는_직원만_결근으로_나온다() {
        Employee attended = new Employee("출근자", null, store, userRepository.save(new User("attended", "pw", Role.EMPLOYEE)));
        attended.approve(10030);
        employeeRepository.save(attended);
        LocalDate day = LocalDate.now().minusDays(1);
        schedule(employee, day, 14);
        schedule(employee, day, 9);
        schedule(attended, day, 9);
        attendanceRepository.save(new Attendance(attended, day.atTime(9, 0)));
        schedule(employee, LocalDate.now().plusDays(1), 9);

        assertThat(attendanceService.findAbsences(store.getId(), day, "owner")).singleElement().satisfies(a -> {
            assertThat(a.employeeId()).isEqualTo(employee.getId());
            assertThat(a.scheduledStart()).isEqualTo(LocalTime.of(9, 0));
        });
        assertThat(attendanceService.findAbsences(store.getId(), LocalDate.now().plusDays(1), "owner")).isEmpty();
    }

    private void schedule(Employee who, LocalDate date, int startHour) {
        Schedule schedule = new Schedule();
        schedule.setStore(store);
        schedule.setEmployee(who);
        schedule.setWorkDate(date);
        schedule.setStartTime(LocalTime.of(startHour, 0));
        schedule.setEndTime(LocalTime.of(startHour + 3, 0));
        scheduleRepository.save(schedule);
    }
}
