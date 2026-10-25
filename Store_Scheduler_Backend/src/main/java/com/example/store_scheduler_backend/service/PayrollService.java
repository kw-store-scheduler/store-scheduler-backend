package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PayrollService {

    private static final long WEEKLY_HOLIDAY_THRESHOLD_MINUTES = 15 * 60;
    private static final long MAX_WEEKLY_HOLIDAY_MINUTES = 8 * 60;

    private final EmployeeRepository employeeRepository;
    private final ScheduleRepository scheduleRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final LaborPolicy laborPolicy;

    public List<EmployeePayroll> calculateMonthlyPayroll(Long storeId, YearMonth yearMonth, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);

        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();

        List<Employee> employees = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
        List<EmployeePayroll> result = new ArrayList<>();
        for (Employee employee : employees) {
            result.add(calculateForEmployee(employee, start, end));
        }
        return result;
    }

    // 직원 본인 화면의 예상 급여용. 권한 확인은 호출하는 쪽에서 한다
    public EmployeePayroll calculateForEmployee(Employee employee, YearMonth yearMonth) {
        return calculateForEmployee(employee, yearMonth.atDay(1), yearMonth.atEndOfMonth());
    }

    EmployeePayroll calculateForEmployee(Employee employee, LocalDate start, LocalDate end) {
        List<Schedule> schedules = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                employee.getId(), start, end);

        long totalMinutes = 0;
        long nightMinutes = 0;
        Map<LocalDate, Long> weeklyMinutes = new HashMap<>();
        for (Schedule schedule : schedules) {
            long minutes = Duration.between(schedule.getStartTime(), schedule.getEndTime()).toMinutes();
            if (minutes < 0) {
                minutes += 24 * 60;
            }
            totalMinutes += minutes;
            nightMinutes += nightMinutes(schedule.getStartTime(), minutes);

            LocalDate weekStart = schedule.getWorkDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeklyMinutes.merge(weekStart, minutes, Long::sum);
        }

        int hourlyWage = employee.getHourlyWage() != null ? employee.getHourlyWage() : 0;
        long regularWage = Math.round(totalMinutes / 60.0 * hourlyWage);

        long weeklyHolidayMinutes = 0;
        for (long minutes : weeklyMinutes.values()) {
            if (minutes >= WEEKLY_HOLIDAY_THRESHOLD_MINUTES) {
                weeklyHolidayMinutes += Math.min(minutes / 5, MAX_WEEKLY_HOLIDAY_MINUTES);
            }
        }
        long weeklyHolidayPay = Math.round(weeklyHolidayMinutes / 60.0 * hourlyWage);

        // 상시 5인 이상이면 야간(22~06시)과 주 40시간을 넘는 연장근무에 50% 가산
        long nightPay = 0;
        long overtimePay = 0;
        if (laborPolicy.premiumApplies(employee.getStore())) {
            nightPay = Math.round(nightMinutes / 60.0 * hourlyWage * LaborPolicy.PREMIUM_RATE);
            long overtimeMinutes = weeklyMinutes.values().stream()
                    .mapToLong(m -> Math.max(0, m - LaborPolicy.WEEKLY_REGULAR_MINUTES))
                    .sum();
            overtimePay = Math.round(overtimeMinutes / 60.0 * hourlyWage * LaborPolicy.PREMIUM_RATE);
        }

        return new EmployeePayroll(
                employee.getId(),
                employee.getName(),
                Math.round(totalMinutes / 60.0 * 100) / 100.0,
                hourlyWage,
                regularWage,
                weeklyHolidayPay,
                nightPay,
                overtimePay,
                regularWage + weeklyHolidayPay + nightPay + overtimePay
        );
    }

    // 시작 시각부터 minutes 동안 중 22:00~06:00에 걸친 분
    static long nightMinutes(LocalTime start, long minutes) {
        long from = start.toSecondOfDay() / 60;
        long to = from + minutes;
        long[][] nights = {{0, 6 * 60}, {22 * 60, 30 * 60}, {46 * 60, 54 * 60}};
        long sum = 0;
        for (long[] n : nights) {
            sum += Math.max(0, Math.min(to, n[1]) - Math.max(from, n[0]));
        }
        return sum;
    }

    @Getter
    @AllArgsConstructor
    public static class EmployeePayroll {
        private Long employeeId;
        private String employeeName;
        private double totalHours;
        private int hourlyWage;
        private long regularWage;
        private long weeklyHolidayPay;
        private long nightPay;
        private long overtimePay;
        private long totalPay;
    }
}
