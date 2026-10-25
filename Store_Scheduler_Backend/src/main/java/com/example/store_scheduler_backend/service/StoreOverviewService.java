package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeSkillLevel;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.SkillLevel;
import com.example.store_scheduler_backend.domain.SkillWindowRequirement;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.EmployeeSkillLevelRepository;
import com.example.store_scheduler_backend.repository.FixedScheduleRepository;
import com.example.store_scheduler_backend.repository.PreferenceSubmissionRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.SkillWindowRequirementRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 점주 근무자 목록(P07), 역량 매칭(M03), 직원 내 정보 요약(H06)
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StoreOverviewService {

    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final ScheduleRepository scheduleRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final PreferenceSubmissionRepository submissionRepository;
    private final ShiftRepository shiftRepository;
    private final SkillWindowRequirementRepository windowRepository;
    private final EmployeeSkillLevelRepository levelRepository;
    private final FixedScheduleRepository fixedScheduleRepository;
    private final PayrollService payrollService;
    private final PreferenceReportService preferenceReportService;
    private final NotificationService notificationService;

    // todayStatus: WORKING(근무 중·예정), VACANCY(결원 신고), OFF(휴무)
    public record EmployeeRow(Employee employee, String todayStatus, Schedule todaySchedule, double monthHours,
                              boolean nextMonthSubmitted) {
    }

    public record Roster(List<EmployeeRow> employees, long pendingApprovals) {
    }

    // 시간대 역량 하나의 그날 배치 결과
    public record Coverage(SkillWindowRequirement requirement, List<Employee> matched, List<SwapRequest> vacancies) {
        public boolean satisfied() {
            return matched.size() >= requirement.getRequiredCount();
        }
    }

    public record MySummaryView(Long employeeId, String name, EmploymentType employmentType, LocalDate startDate,
                                Integer hourlyWage, String storeName, int monthDays, double monthHours, long payToDate,
                                double cumulativeHours, int fixedScheduleCount, Double lastReportRate, int lastReportHonored,
                                int lastReportAssigned, long unreadNotifications, boolean pushEnabled) {
    }

    public record MySummary(Employee employee, int monthDays, double monthHours, long payToDate, double cumulativeHours,
                            int fixedScheduleCount, Double lastReportRate, int lastReportHonored, int lastReportAssigned,
                            long unreadNotifications, boolean pushEnabled) {
    }

    public Roster getRoster(Long storeId, LocalDate date, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        YearMonth month = YearMonth.from(date);
        Map<Long, Schedule> today = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, date, date).stream()
                .collect(Collectors.toMap(s -> s.getEmployee().getId(), Function.identity(), (a, b) -> a));
        Set<Long> absent = swapRequestRepository.findByStoreIdOrderByCreatedAtDesc(storeId).stream()
                .filter(r -> r.isActive() && r.getWorkDate().equals(date))
                .map(r -> r.getRequester().getId())
                .collect(Collectors.toSet());
        Set<Long> submitted = submissionRepository.findByEmployee_StoreIdAndMonth(storeId, month.plusMonths(1).atDay(1)).stream()
                .map(s -> s.getEmployee().getId())
                .collect(Collectors.toSet());
        Map<Long, Double> monthHours = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, month.atDay(1), month.atEndOfMonth())
                .stream()
                .collect(Collectors.groupingBy(s -> s.getEmployee().getId(),
                        Collectors.summingDouble(s -> minutes(s.getStartTime(), s.getEndTime()) / 60.0)));
        List<EmployeeRow> rows = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED).stream()
                .map(e -> new EmployeeRow(e,
                        absent.contains(e.getId()) ? "VACANCY" : today.containsKey(e.getId()) ? "WORKING" : "OFF",
                        today.get(e.getId()), Math.round(monthHours.getOrDefault(e.getId(), 0.0) * 10) / 10.0,
                        submitted.contains(e.getId())))
                // 직원(직책 순) → 알바, 그다음 이름 순
                .sorted(Comparator.comparing((EmployeeRow r) -> r.employee().getEmploymentType() != EmploymentType.FULL_TIME)
                        .thenComparing(r -> r.employee().getPosition() == null)
                        .thenComparing(r -> r.employee().getPosition() != null ? r.employee().getPosition().ordinal() : 0)
                        .thenComparing(r -> r.employee().getName()))
                .toList();
        return new Roster(rows, employeeRepository.countByStoreIdAndStatus(storeId, EmployeeStatus.PENDING));
    }

    // 그날 시간대 역량마다 겹치는 교대에 배치된 직원 중 조건에 맞는 사람과 그 교대의 결원
    public List<Coverage> getCoverage(Long storeId, LocalDate date, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        List<Shift> shifts = shiftRepository.findByStoreId(storeId);
        List<Schedule> today = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, date, date);
        List<SwapRequest> vacancies = swapRequestRepository.findByStoreIdOrderByCreatedAtDesc(storeId).stream()
                .filter(r -> r.isActive() && r.getWorkDate().equals(date))
                .toList();
        Map<Long, List<EmployeeSkillLevel>> levels = levelRepository.findByEmployee_StoreId(storeId).stream()
                .collect(Collectors.groupingBy(l -> l.getEmployee().getId()));
        return windowRepository.findByStoreIdOrderByStartTimeAsc(storeId).stream()
                .filter(w -> w.appliesOn(date.getDayOfWeek()))
                .map(w -> {
                    List<Shift> covering = shifts.stream().filter(s -> overlaps(s, w.getStartTime(), w.getEndTime())).toList();
                    List<Schedule> inWindow = today.stream()
                            .filter(s -> covering.stream().anyMatch(c -> sameTime(c, s)))
                            .filter(s -> vacancies.stream().noneMatch(v -> v.getSchedule() != null && v.getSchedule().getId().equals(s.getId())))
                            .toList();
                    List<Employee> matched = inWindow.stream()
                            .map(Schedule::getEmployee)
                            .distinct()
                            .filter(e -> meets(e, w, levels.getOrDefault(e.getId(), List.of())))
                            .toList();
                    List<SwapRequest> windowVacancies = vacancies.stream()
                            .filter(v -> v.getStartTime().isBefore(w.getEndTime()) && w.getStartTime().isBefore(v.getEndTime()))
                            .toList();
                    return new Coverage(w, matched, windowVacancies);
                })
                .toList();
    }

    public MySummary getMySummary(Long storeId, LocalDate today, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        YearMonth month = YearMonth.from(today);
        List<Schedule> monthSchedules = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                me.getId(), month.atDay(1), month.atEndOfMonth());
        double monthHours = monthSchedules.stream().mapToDouble(s -> minutes(s.getStartTime(), s.getEndTime()) / 60.0).sum();
        long payToDate = payrollService.calculateForEmployee(me, month.atDay(1), today).getTotalPay();
        double cumulative = scheduleRepository.findByStoreIdAndEmployeeIdOrderByWorkDateAscStartTimeAsc(storeId, me.getId()).stream()
                .filter(s -> !s.getWorkDate().isAfter(today))
                .mapToDouble(s -> minutes(s.getStartTime(), s.getEndTime()) / 60.0)
                .sum();
        PreferenceReportService.Report last = preferenceReportService.getMine(storeId, month.minusMonths(1), username);
        return new MySummary(me, (int) monthSchedules.stream().map(Schedule::getWorkDate).distinct().count(),
                Math.round(monthHours * 10) / 10.0, payToDate, Math.round(cumulative * 10) / 10.0,
                fixedScheduleRepository.findByEmployeeId(me.getId()).size(),
                last.preferenceRate(), last.honoredCount(), last.assignedCount(),
                notificationService.countUnread(username), me.getUser().isNotificationsEnabled());
    }

    private static boolean meets(Employee employee, SkillWindowRequirement w, List<EmployeeSkillLevel> levels) {
        if (w.getPermission() != null) {
            return employee.getPermissionList().contains(w.getPermission());
        }
        Optional<EmployeeSkillLevel> level = levels.stream().filter(l -> l.getSkill().getId().equals(w.getSkill().getId())).findFirst();
        SkillLevel actual = EmployeeProfileService.effectiveLevel(employee, w.getSkill(), level.orElse(null));
        if (w.getMinLevel() == null) {
            return actual != SkillLevel.NONE;
        }
        return actual != null && actual.atLeast(w.getMinLevel());
    }

    private static boolean overlaps(Shift shift, LocalTime from, LocalTime to) {
        int start = shift.getStartTime().toSecondOfDay() / 60;
        return start < to.toSecondOfDay() / 60 && from.toSecondOfDay() / 60 < ScheduleAutomationService.endMinutes(shift);
    }

    private static boolean sameTime(Shift shift, Schedule schedule) {
        return shift.getStartTime().equals(schedule.getStartTime()) && shift.getEndTime().equals(schedule.getEndTime());
    }

    private static long minutes(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        return minutes <= 0 ? minutes + 24 * 60 : minutes;
    }
}
