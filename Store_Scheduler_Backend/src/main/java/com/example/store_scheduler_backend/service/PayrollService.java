package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.WageChange;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.WageChangeRepository;
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
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PayrollService {

    private static final long WEEKLY_HOLIDAY_THRESHOLD_MINUTES = 15 * 60;
    private static final long MAX_WEEKLY_HOLIDAY_MINUTES = 8 * 60;
    private static final long DAILY_REGULAR_MINUTES = 8 * 60;

    private final EmployeeRepository employeeRepository;
    private final ScheduleRepository scheduleRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final LaborPolicy laborPolicy;
    private final WageChangeRepository wageChangeRepository;

    public List<EmployeePayroll> calculateMonthlyPayroll(Long storeId, YearMonth yearMonth, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);

        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();

        List<Employee> employees = employeeRepository.findByStoreIdAndStatusIn(storeId,
                List.of(EmployeeStatus.APPROVED, EmployeeStatus.RESIGNED));
        List<EmployeePayroll> result = new ArrayList<>();
        for (Employee employee : employees) {
            EmployeePayroll payroll = calculateForEmployee(employee, start, end);
            // 퇴사한 직원은 그달 근무가 있을 때만 정산 대상
            if (employee.getStatus() == EmployeeStatus.APPROVED || payroll.getTotalPay() > 0) {
                result.add(payroll);
            }
        }
        return result;
    }

    // 직원 본인 화면의 예상 급여용. 권한 확인은 호출하는 쪽에서 한다
    public EmployeePayroll calculateForEmployee(Employee employee, YearMonth yearMonth) {
        return calculateForEmployee(employee, yearMonth.atDay(1), yearMonth.atEndOfMonth());
    }

    EmployeePayroll calculateForEmployee(Employee employee, LocalDate start, LocalDate end) {
        // 월을 걸친 주도 주 전체 시간으로 판단해야 하므로 앞뒤 주까지 읽는다
        List<Schedule> schedules = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employee.getId(),
                start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                end.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
        List<WageChange> wageChanges = wageChangeRepository.findByEmployeeIdOrderByIdAsc(employee.getId());
        boolean deductBreak = employee.getStore().isBreakTimeDeducted();

        long totalMinutes = 0;
        double regularWage = 0;
        double nightWage = 0;
        Map<LocalDate, Long> minutesByDay = new HashMap<>();
        for (Schedule schedule : schedules) {
            long worked = Duration.between(schedule.getStartTime(), schedule.getEndTime()).toMinutes();
            if (worked < 0) {
                worked += 24 * 60;
            }
            long paid = deductBreak ? worked - breakMinutes(worked) : worked;
            LocalDate date = schedule.getWorkDate();
            minutesByDay.merge(date, paid, Long::sum);
            if (!date.isBefore(start) && !date.isAfter(end)) {
                int wage = wageOn(employee, wageChanges, date);
                totalMinutes += paid;
                regularWage += paid / 60.0 * wage;
                nightWage += nightMinutes(schedule.getStartTime(), worked) / 60.0 * wage;
            }
        }

        Map<LocalDate, List<LocalDate>> daysByWeek = minutesByDay.keySet().stream()
                .collect(Collectors.groupingBy(d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))));
        boolean premium = laborPolicy.premiumApplies(employee.getStore());
        double weeklyHolidayPay = 0;
        double overtimePay = 0;
        // 주휴수당과 연장 가산은 그 주가 끝나는 날(일요일)이 속한 기간에 넣어 두 달에 중복되지 않게 한다
        for (Map.Entry<LocalDate, List<LocalDate>> week : daysByWeek.entrySet()) {
            LocalDate sunday = week.getKey().plusDays(6);
            if (sunday.isBefore(start) || sunday.isAfter(end)) {
                continue;
            }
            int wage = wageOn(employee, wageChanges, sunday);
            long weekMinutes = week.getValue().stream().mapToLong(minutesByDay::get).sum();
            if (weekMinutes >= WEEKLY_HOLIDAY_THRESHOLD_MINUTES) {
                weeklyHolidayPay += Math.min(weekMinutes / 5, MAX_WEEKLY_HOLIDAY_MINUTES) / 60.0 * wage;
            }
            if (premium) {
                overtimePay += overtimeMinutes(week.getValue(), minutesByDay) / 60.0 * wage * LaborPolicy.PREMIUM_RATE;
            }
        }

        // 상시 5인 이상이면 야간(22~06시), 연장, 공휴일 근무에 가산
        long nightPay = premium ? Math.round(nightWage * LaborPolicy.PREMIUM_RATE) : 0;
        double holidayPay = 0;
        if (premium) {
            for (Map.Entry<LocalDate, Long> day : minutesByDay.entrySet()) {
                LocalDate date = day.getKey();
                if (KoreanHolidays.isHoliday(date) && !date.isBefore(start) && !date.isAfter(end)) {
                    holidayPay += holidayPremiumMinutes(day.getValue()) / 60.0 * wageOn(employee, wageChanges, date);
                }
            }
        }

        long regular = Math.round(regularWage);
        long weeklyHoliday = Math.round(weeklyHolidayPay);
        long overtime = Math.round(overtimePay);
        long holiday = Math.round(holidayPay);
        return new EmployeePayroll(
                employee.getId(),
                employee.getName(),
                Math.round(totalMinutes / 60.0 * 100) / 100.0,
                employee.getHourlyWage() != null ? employee.getHourlyWage() : 0,
                regular,
                weeklyHoliday,
                nightPay,
                overtime,
                holiday,
                regular + weeklyHoliday + nightPay + overtime + holiday
        );
    }

    // 하루 8시간 초과와 주 40시간 초과 중 큰 쪽. 공휴일 근무는 휴일 가산으로 따로 계산한다
    private static long overtimeMinutes(List<LocalDate> days, Map<LocalDate, Long> minutesByDay) {
        long weekly = 0;
        long dailyExcess = 0;
        for (LocalDate day : days) {
            if (!KoreanHolidays.isHoliday(day)) {
                long minutes = minutesByDay.get(day);
                weekly += minutes;
                dailyExcess += Math.max(0, minutes - DAILY_REGULAR_MINUTES);
            }
        }
        return Math.max(dailyExcess, weekly - LaborPolicy.WEEKLY_REGULAR_MINUTES);
    }

    // 가산 분을 배율 1 기준으로: 8시간까지 50%, 초과분 100%
    private static double holidayPremiumMinutes(long minutes) {
        return Math.min(minutes, DAILY_REGULAR_MINUTES) * LaborPolicy.PREMIUM_RATE + Math.max(0, minutes - DAILY_REGULAR_MINUTES);
    }

    static long breakMinutes(long worked) {
        return worked >= DAILY_REGULAR_MINUTES ? 60 : worked >= 4 * 60 ? 30 : 0;
    }

    // 이후 변경 중 가장 이른 것의 이전 시급이 그날 시급. 이후 변경이 없으면 현재 시급
    private static int wageOn(Employee employee, List<WageChange> changes, LocalDate date) {
        return changes.stream()
                .filter(c -> c.getEffectiveFrom().isAfter(date))
                .findFirst()
                .map(WageChange::getPreviousWage)
                .orElse(employee.getHourlyWage() != null ? employee.getHourlyWage() : 0);
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
        private long holidayPay;
        private long totalPay;
    }
}
