package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
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
    private final StoreRepository storeRepository;

    public List<EmployeePayroll> calculateMonthlyPayroll(Long storeId, YearMonth yearMonth, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();

        List<Employee> employees = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
        List<EmployeePayroll> result = new ArrayList<>();
        for (Employee employee : employees) {
            result.add(calculateForEmployee(employee, start, end));
        }
        return result;
    }

    private EmployeePayroll calculateForEmployee(Employee employee, LocalDate start, LocalDate end) {
        List<Schedule> schedules = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                employee.getId(), start, end);

        long totalMinutes = 0;
        Map<LocalDate, Long> weeklyMinutes = new HashMap<>();
        for (Schedule schedule : schedules) {
            long minutes = Duration.between(schedule.getStartTime(), schedule.getEndTime()).toMinutes();
            if (minutes < 0) {
                minutes += 24 * 60;
            }
            totalMinutes += minutes;

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

        return new EmployeePayroll(
                employee.getId(),
                employee.getName(),
                Math.round(totalMinutes / 60.0 * 100) / 100.0,
                hourlyWage,
                regularWage,
                weeklyHolidayPay,
                regularWage + weeklyHolidayPay
        );
    }

    private Store findStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다."));
    }

    private void verifyOwner(Store store, String username) {
        if (!store.getOwner().getUsername().equals(username)) {
            throw new IllegalArgumentException("해당 매장의 관리자만 접근할 수 있습니다.");
        }
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
        private long totalPay;
    }
}
