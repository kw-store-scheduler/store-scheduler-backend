package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.FixedSchedule;
import com.example.store_scheduler_backend.domain.PreferenceLevel;
import com.example.store_scheduler_backend.domain.PreferencePriority;
import com.example.store_scheduler_backend.domain.PreferenceSubmission;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftPreference;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.FixedScheduleRepository;
import com.example.store_scheduler_backend.repository.PreferenceSubmissionRepository;
import com.example.store_scheduler_backend.repository.ShiftPreferenceRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// 다음 달 근무 희망 제출. 날짜×교대 칸(가능/선호), 매주 고정 일정, 개인 근무 규칙과 우선순위를 받는다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PreferenceService {

    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftPreferenceRepository preferenceRepository;
    private final FixedScheduleRepository fixedScheduleRepository;
    private final PreferenceSubmissionRepository submissionRepository;
    private final StaffingPlanService staffingPlanService;

    public record FixedScheduleInput(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String label) {
    }

    public record SlotInput(LocalDate date, Long shiftId, PreferenceLevel level) {
    }

    public record SubmissionInput(List<SlotInput> slots, Integer maxWeekendShifts, Integer minMonthlyHours,
                                  Integer maxMonthlyHours, Integer maxConsecutiveDays, boolean noOpenAfterClose,
                                  List<PreferencePriority> priorities) {
    }

    // level이 null이면 불가. locked면 고정 일정이나 미성년자 제한으로 고를 수 없는 칸
    public record Cell(LocalDate date, Shift shift, PreferenceLevel level, boolean locked, String lockLabel) {
    }

    public record MonthView(Employee employee, YearMonth month, LocalDate deadline, List<Shift> shifts, List<Cell> cells,
                            PreferenceSubmission submission) {
    }

    public record EmployeeStatusView(Employee employee, PreferenceSubmission submission, int availableCount,
                                     int preferredCount, boolean weekendAvailable) {
    }

    public record StoreView(YearMonth month, LocalDate deadline, List<EmployeeStatusView> employees) {
        public long submittedCount() {
            return employees.stream().filter(e -> e.submission() != null).count();
        }
    }

    public List<FixedSchedule> findMyFixedSchedules(Long storeId, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        return sortedFixed(fixedScheduleRepository.findByEmployeeId(employee.getId()));
    }

    // 고정 일정을 통째로 교체한다. 이미 고른 칸 중 새 고정 일정과 겹치는 칸은 근무표 생성 때 불가로 본다
    @Transactional
    public List<FixedSchedule> replaceMyFixedSchedules(Long storeId, List<FixedScheduleInput> inputs, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        List<FixedSchedule> rows = new ArrayList<>();
        for (FixedScheduleInput input : inputs) {
            if (input.dayOfWeek() == null || input.startTime() == null || input.endTime() == null
                    || !input.endTime().isAfter(input.startTime())) {
                throw new IllegalArgumentException("고정 일정의 요일과 시작·종료 시간을 확인해주세요.");
            }
            String label = input.label() != null && !input.label().isBlank() ? input.label() : "고정 일정";
            rows.add(new FixedSchedule(employee, input.dayOfWeek(), input.startTime(), input.endTime(), label));
        }
        fixedScheduleRepository.deleteAll(fixedScheduleRepository.findByEmployeeId(employee.getId()));
        fixedScheduleRepository.flush();
        return sortedFixed(fixedScheduleRepository.saveAll(rows));
    }

    public MonthView getMine(Long storeId, YearMonth month, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        return buildView(employee, month);
    }

    // 해당 월의 칸을 통째로 교체하고 제출 시각을 남긴다. 마감 이후 제출도 받되 점주 화면에서 늦은 제출로 보인다
    @Transactional
    public MonthView submit(Long storeId, YearMonth month, SubmissionInput input, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        validateRules(input);
        Map<String, Cell> cellByKey = new HashMap<>();
        for (Cell cell : buildCells(employee, month, sortedShifts(storeId))) {
            cellByKey.put(cellKey(cell.date(), cell.shift().getId()), cell);
        }

        List<ShiftPreference> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SlotInput slot : input.slots()) {
            if (slot.date() == null || slot.shiftId() == null || slot.level() == null) {
                throw new IllegalArgumentException("칸마다 날짜, 교대, 가능/선호를 입력해주세요.");
            }
            String key = cellKey(slot.date(), slot.shiftId());
            Cell cell = cellByKey.get(key);
            if (cell == null) {
                throw new IllegalArgumentException(slot.date() + " 해당 교대는 " + month + " 근무 칸이 아닙니다.");
            }
            if (cell.locked()) {
                throw new IllegalArgumentException(slot.date() + " " + cell.shift().getName() + "은(는) "
                        + cell.lockLabel() + " 때문에 고를 수 없습니다.");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("같은 칸이 중복되었습니다: " + slot.date() + " " + cell.shift().getName());
            }
            rows.add(new ShiftPreference(employee, slot.date(), cell.shift(), slot.level()));
        }

        preferenceRepository.deleteAll(preferenceRepository.findByEmployeeIdAndDateBetween(
                employee.getId(), month.atDay(1), month.atEndOfMonth()));
        preferenceRepository.flush();
        preferenceRepository.saveAll(rows);

        PreferenceSubmission submission = submissionRepository.findByEmployeeIdAndMonth(employee.getId(), month.atDay(1))
                .orElseGet(() -> new PreferenceSubmission(employee, month.atDay(1)));
        submission.update(input.maxWeekendShifts(), input.minMonthlyHours(), input.maxMonthlyHours(),
                input.maxConsecutiveDays(), input.noOpenAfterClose(),
                input.priorities() != null ? input.priorities().stream().distinct().toList() : List.of());
        submissionRepository.save(submission);
        return buildView(employee, month);
    }

    public StoreView getStoreStatus(Long storeId, YearMonth month, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        Map<Long, PreferenceSubmission> submissionByEmployee = new HashMap<>();
        submissionRepository.findByEmployee_StoreIdAndMonth(storeId, month.atDay(1))
                .forEach(s -> submissionByEmployee.put(s.getEmployee().getId(), s));
        Map<Long, List<ShiftPreference>> preferencesByEmployee = new HashMap<>();
        preferenceRepository.findByEmployee_StoreIdAndDateBetween(storeId, month.atDay(1), month.atEndOfMonth())
                .forEach(p -> preferencesByEmployee.computeIfAbsent(p.getEmployee().getId(), k -> new ArrayList<>()).add(p));

        List<EmployeeStatusView> employees = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED).stream()
                .map(e -> {
                    List<ShiftPreference> own = preferencesByEmployee.getOrDefault(e.getId(), List.of());
                    return new EmployeeStatusView(e, submissionByEmployee.get(e.getId()),
                            (int) own.stream().filter(p -> p.getLevel() == PreferenceLevel.AVAILABLE).count(),
                            (int) own.stream().filter(p -> p.getLevel() == PreferenceLevel.PREFERRED).count(),
                            own.stream().anyMatch(p -> isWeekend(p.getDate())));
                })
                .toList();
        return new StoreView(month, deadlineOf(store, month), employees);
    }

    // 다음 달 희망은 이번 달 마감일까지 받는다
    public static LocalDate deadlineOf(Store store, YearMonth month) {
        return month.minusMonths(1).atDay(store.getPreferenceDeadlineDayOrDefault());
    }

    public static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private MonthView buildView(Employee employee, YearMonth month) {
        List<Shift> shifts = sortedShifts(employee.getStore().getId());
        return new MonthView(employee, month, deadlineOf(employee.getStore(), month), shifts,
                buildCells(employee, month, shifts),
                submissionRepository.findByEmployeeIdAndMonth(employee.getId(), month.atDay(1)).orElse(null));
    }

    // 그달 매장이 여는 (날짜, 교대) 칸 전부. 요일 전용 교대나 예외일로 0명인 칸은 빠진다
    private List<Cell> buildCells(Employee employee, YearMonth month, List<Shift> shifts) {
        StaffingPlanService.Plan plan = staffingPlanService.planFor(employee.getStore().getId(), month.atDay(1),
                month.atEndOfMonth());
        List<FixedSchedule> fixed = fixedScheduleRepository.findByEmployeeId(employee.getId());
        Map<String, PreferenceLevel> levelByKey = new HashMap<>();
        preferenceRepository.findByEmployeeIdAndDateBetween(employee.getId(), month.atDay(1), month.atEndOfMonth())
                .forEach(p -> levelByKey.put(cellKey(p.getDate(), p.getShift().getId()), p.getLevel()));

        List<Cell> cells = new ArrayList<>();
        for (LocalDate date = month.atDay(1); !date.isAfter(month.atEndOfMonth()); date = date.plusDays(1)) {
            for (Shift shift : shifts) {
                if (plan.of(shift, date).target() == 0) {
                    continue;
                }
                Optional<String> lock = lockOf(employee, fixed, shift, date);
                cells.add(new Cell(date, shift, lock.isPresent() ? null : levelByKey.get(cellKey(date, shift.getId())),
                        lock.isPresent(), lock.orElse(null)));
            }
        }
        return cells;
    }

    static Optional<String> lockOf(Employee employee, List<FixedSchedule> fixed, Shift shift, LocalDate date) {
        if (employee.isMinor() && ScheduleAutomationService.isRestrictedForMinor(shift.getStartTime(), shift.getEndTime())) {
            return Optional.of("미성년자 근무 제한");
        }
        return fixed.stream()
                .filter(f -> f.getDayOfWeek() == date.getDayOfWeek() && f.overlaps(shift.getStartTime(), shift.getEndTime()))
                .map(FixedSchedule::getLabel)
                .findFirst();
    }

    private static void validateRules(SubmissionInput input) {
        if (input.slots() == null) {
            throw new IllegalArgumentException("근무 가능한 칸 목록을 보내주세요. 없으면 빈 목록입니다.");
        }
        if (input.minMonthlyHours() != null && input.maxMonthlyHours() != null
                && input.minMonthlyHours() > input.maxMonthlyHours()) {
            throw new IllegalArgumentException("최소 근무 시간은 최대 근무 시간보다 클 수 없습니다.");
        }
        if (isNegative(input.maxWeekendShifts()) || isNegative(input.minMonthlyHours()) || isNegative(input.maxMonthlyHours())
                || (input.maxConsecutiveDays() != null && input.maxConsecutiveDays() < 1)) {
            throw new IllegalArgumentException("근무 규칙 값을 확인해주세요.");
        }
    }

    private static boolean isNegative(Integer value) {
        return value != null && value < 0;
    }

    private List<Shift> sortedShifts(Long storeId) {
        return shiftRepository.findByStoreId(storeId).stream()
                .sorted(Comparator.comparing(Shift::getStartTime).thenComparing(Shift::getId))
                .toList();
    }

    private static List<FixedSchedule> sortedFixed(List<FixedSchedule> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(FixedSchedule::getDayOfWeek).thenComparing(FixedSchedule::getStartTime))
                .toList();
    }

    private static String cellKey(LocalDate date, Long shiftId) {
        return date + "|" + shiftId;
    }
}
