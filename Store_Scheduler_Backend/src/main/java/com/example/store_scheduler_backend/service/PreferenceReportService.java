package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.PreferenceLevel;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftPreference;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftPreferenceRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// 확정된 근무표가 내 희망을 얼마나 반영했는지. 후보안 비교의 희망 반영 지표와 같은 기준으로 센다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PreferenceReportService {

    private static final int BURDEN_WEEKS = 4;

    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final ScheduleRepository scheduleRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftPreferenceRepository preferenceRepository;
    private final DayOffRequestRepository dayOffRequestRepository;

    // 선호한 교대가 있던 날 다른 교대에 배정된 근무
    public record Unfulfilled(Schedule schedule, String assignedShiftName, List<String> preferredShiftNames,
                              List<String> reasons) {
    }

    // 최근 4주 동안 부담이 큰 근무 횟수. 팀 평균은 승인된 직원 전체 기준
    public record Burden(String kind, int mine, double teamAverage) {
    }

    public record Report(YearMonth month, int assignedCount, int honoredCount, boolean submitted, int dayOffRequested,
                         int dayOffHonored, int mustRequested, int preferRequested, List<Burden> burdens,
                         LocalDate burdenFrom, LocalDate burdenTo, List<Unfulfilled> unfulfilled) {
        public Double preferenceRate() {
            if (!submitted) {
                return null;
            }
            return assignedCount == 0 ? 100.0 : Math.round(honoredCount * 1000.0 / assignedCount) / 10.0;
        }

        public Double dayOffRate() {
            return dayOffRequested == 0 ? null : Math.round(dayOffHonored * 1000.0 / dayOffRequested) / 10.0;
        }
    }

    public Report getMine(Long storeId, YearMonth month, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        List<Shift> shifts = shiftRepository.findByStoreId(storeId).stream()
                .sorted(Comparator.comparing(Shift::getStartTime).thenComparing(Shift::getId))
                .toList();
        List<Schedule> mine = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employee.getId(), from, to);
        List<ShiftPreference> preferences = preferenceRepository.findByEmployeeIdAndDateBetween(employee.getId(), from, to);
        Map<LocalDate, List<ShiftPreference>> preferencesByDate = preferences.stream()
                .collect(Collectors.groupingBy(ShiftPreference::getDate));

        int honored = 0;
        List<Unfulfilled> unfulfilled = new ArrayList<>();
        for (Schedule schedule : mine) {
            List<ShiftPreference> sameDay = preferencesByDate.getOrDefault(schedule.getWorkDate(), List.of());
            List<String> preferred = sameDay.stream()
                    .filter(p -> p.getLevel() == PreferenceLevel.PREFERRED)
                    .map(p -> p.getShift().getName())
                    .toList();
            boolean preferredThis = sameDay.stream().anyMatch(p -> p.getLevel() == PreferenceLevel.PREFERRED
                    && sameTime(p.getShift(), schedule.getStartTime(), schedule.getEndTime()));
            if (preferredThis || preferred.isEmpty()) {
                honored++;
            } else {
                unfulfilled.add(new Unfulfilled(schedule, HomeService.shiftName(shifts, schedule), preferred,
                        schedule.getAssignmentReasons() == null ? List.of()
                                : List.of(schedule.getAssignmentReasons().split("\n"))));
            }
        }

        List<DayOffRequest> dayOffs = dayOffRequestRepository.findByEmployeeIdOrderByDateAsc(employee.getId()).stream()
                .filter(r -> !r.getDate().isBefore(from) && !r.getDate().isAfter(to))
                .toList();
        Set<LocalDate> workDays = mine.stream().map(Schedule::getWorkDate).collect(Collectors.toSet());
        int dayOffHonored = (int) dayOffs.stream().filter(r -> !workDays.contains(r.getDate())).count();

        LocalDate burdenTo = to;
        LocalDate burdenFrom = to.minusWeeks(BURDEN_WEEKS).plusDays(1);
        return new Report(month, mine.size(), honored, !preferences.isEmpty(), dayOffs.size(), dayOffHonored,
                (int) dayOffs.stream().filter(r -> r.getType() == DayOffType.MUST).count(),
                (int) dayOffs.stream().filter(r -> r.getType() == DayOffType.PREFER).count(),
                burdens(storeId, employee, shifts, burdenFrom, burdenTo), burdenFrom, burdenTo, unfulfilled);
    }

    private List<Burden> burdens(Long storeId, Employee employee, List<Shift> shifts, LocalDate from, LocalDate to) {
        List<Schedule> all = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, from, to);
        int teamSize = Math.max(1, (int) employeeRepository.countByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED));
        // 근무표 생성과 같이 가장 늦게 끝나는 교대를 마감으로 본다
        int latestEnd = shifts.stream().mapToInt(ScheduleAutomationService::endMinutes).max().orElse(-1);
        List<Shift> closeShifts = shifts.stream().filter(s -> ScheduleAutomationService.endMinutes(s) == latestEnd).toList();
        List<Burden> result = new ArrayList<>();
        result.add(burden("WEEKEND", all, employee, teamSize, s -> PreferenceService.isWeekend(s.getWorkDate())));
        result.add(burden("CLOSE", all, employee, teamSize,
                s -> closeShifts.stream().anyMatch(c -> sameTime(c, s.getStartTime(), s.getEndTime()))));
        result.add(burden("NIGHT", all, employee, teamSize,
                s -> ScheduleAutomationService.isNightShift(s.getStartTime(), s.getEndTime())));
        return result;
    }

    private Burden burden(String kind, List<Schedule> all, Employee employee, int teamSize,
                          Predicate<Schedule> matches) {
        List<Schedule> matched = all.stream().filter(matches).toList();
        int mine = (int) matched.stream().filter(s -> s.getEmployee().getId().equals(employee.getId())).count();
        return new Burden(kind, mine, Math.round(matched.size() * 10.0 / teamSize) / 10.0);
    }

    private static boolean sameTime(Shift shift, LocalTime start, LocalTime end) {
        return shift.getStartTime().equals(start) && shift.getEndTime().equals(end);
    }
}
