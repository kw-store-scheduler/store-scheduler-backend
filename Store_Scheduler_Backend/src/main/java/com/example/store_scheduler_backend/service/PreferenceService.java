package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.ContractExceedStatus;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.FixedSchedule;
import com.example.store_scheduler_backend.domain.PreferenceLevel;
import com.example.store_scheduler_backend.domain.PreferencePriority;
import com.example.store_scheduler_backend.domain.PreferenceSubmission;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftPreference;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.DayOffFallback;
import com.example.store_scheduler_backend.domain.FixedScheduleCategory;
import com.example.store_scheduler_backend.domain.ScheduleCycle;
import com.example.store_scheduler_backend.domain.ShiftAttitude;
import com.example.store_scheduler_backend.domain.UnavailablePeriod;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UnavailablePeriodRepository;
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
import java.time.temporal.ChronoUnit;
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
    private final UnavailablePeriodRepository periodRepository;
    private final DayOffRequestRepository dayOffRequestRepository;
    private final LeaveService leaveService;
    private final StoreRepository storeRepository;
    private final FcmService fcmService;

    private static final double WEEKLY_HOLIDAY_THRESHOLD_HOURS = 15;

    public record FixedScheduleInput(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String label,
                                     FixedScheduleCategory category, LocalDate validFrom, LocalDate validUntil) {
        public FixedScheduleInput(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String label) {
            this(dayOfWeek, startTime, endTime, label, null, null, null);
        }
    }

    public record PeriodInput(LocalDate startDate, LocalDate endDate, boolean weekdaysOnly, String note) {
    }

    // 이번 달 근무량 화면: 계약 범위를 이번 달 시간으로 바꾼 값과 예상 급여(주휴 포함) 범위
    public record Workload(Integer contractMinWeekly, Integer contractMaxWeekly, Integer contractMinMonthly,
                           Integer contractMaxMonthly, int hourlyWage, Integer minMonthly, Integer maxMonthly,
                           Long expectedPayMin, Long expectedPayMax, boolean belowWeeklyHolidayThreshold,
                           boolean exceedsContract) {
    }

    // 정규직 연차·휴무 화면: 남은 연차, 이번 달 신청에서 최대로 쓸 연차, 그때 남는 연차
    public record LeavePlan(Integer remaining, double maxUse, Double minRemaining) {
    }

    public record SlotInput(LocalDate date, Long shiftId, PreferenceLevel level) {
    }

    public record SubmissionInput(List<SlotInput> slots, Integer maxWeekendShifts, Integer minMonthlyHours,
                                  Integer maxMonthlyHours, Integer maxConsecutiveDays, boolean noOpenAfterClose,
                                  List<PreferencePriority> priorities, Map<Long, ShiftAttitude> shiftAttitudes,
                                  boolean restDaysTogether) {
        public SubmissionInput(List<SlotInput> slots, Integer maxWeekendShifts, Integer minMonthlyHours,
                               Integer maxMonthlyHours, Integer maxConsecutiveDays, boolean noOpenAfterClose,
                               List<PreferencePriority> priorities) {
            this(slots, maxWeekendShifts, minMonthlyHours, maxMonthlyHours, maxConsecutiveDays, noOpenAfterClose,
                    priorities, Map.of(), false);
        }
    }

    // level이 null이면 불가. locked면 고정 일정이나 미성년자 제한으로 고를 수 없는 칸
    public record Cell(LocalDate date, Shift shift, PreferenceLevel level, boolean locked, String lockLabel) {
    }

    public record MonthView(Employee employee, YearMonth month, LocalDate deadline, List<Shift> shifts, List<Cell> cells,
                            PreferenceSubmission submission) {
    }

    public record EmployeeStatusView(Employee employee, PreferenceSubmission submission, int availableCount,
                                     int preferredCount, boolean weekendAvailable) {
        public ContractExceedStatus contractExceedStatus() {
            return submission != null ? submission.getContractExceedStatus() : null;
        }

        // 계약 상한을 넘겨 신청했고 아직 결정하지 않음
        public boolean needsContractApproval() {
            return contractExceedStatus() == ContractExceedStatus.PENDING;
        }
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
            if (input.validFrom() != null && input.validUntil() != null && input.validUntil().isBefore(input.validFrom())) {
                throw new IllegalArgumentException("고정 일정 적용 기간을 확인해주세요.");
            }
            rows.add(new FixedSchedule(employee, input.dayOfWeek(), input.startTime(), input.endTime(), label,
                    input.category(), input.validFrom(), input.validUntil()));
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
        Integer previousMax = submission.getMaxMonthlyHours();
        submission.update(input.maxWeekendShifts(), input.minMonthlyHours(), input.maxMonthlyHours(),
                input.maxConsecutiveDays(), input.noOpenAfterClose(),
                input.priorities() != null ? input.priorities().stream().distinct().toList() : List.of());
        Map<Long, ShiftAttitude> attitudes = input.shiftAttitudes() != null ? input.shiftAttitudes() : Map.of();
        if (attitudes.keySet().stream().anyMatch(id -> cellByKey.values().stream().noneMatch(c -> c.shift().getId().equals(id)))) {
            throw new IllegalArgumentException("매장에 없는 교대가 있습니다.");
        }
        submission.updateFullTimeOptions(attitudes, input.restDaysTogether());
        // 계약 상한을 넘는 근무량은 점주 승인을 기다린다. 같은 시간으로 다시 제출하면 이미 내린 결정을 유지한다
        Integer contractMax = contractMonthly(employee.getContractMaxWeeklyHours(), month);
        if (contractMax == null || input.maxMonthlyHours() == null || input.maxMonthlyHours() <= contractMax) {
            submission.clearContractExceed();
        } else if (submission.getContractExceedStatus() == null || !input.maxMonthlyHours().equals(previousMax)) {
            submission.requestContractExceed();
            fcmService.send(employee.getStore().getOwner(), "계약 초과 근무 승인 필요",
                    employee.getDisplayName() + "님이 " + month + " 근무를 계약보다 많은 " + input.maxMonthlyHours() + "시간까지 신청했어요.");
        }
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

    // 점주: 계약 상한을 넘긴 근무량 신청 승인·거절. 거절하면 근무표는 계약 상한까지만 배정한다
    @Transactional
    public void decideContractExceed(Long storeId, Long employeeId, YearMonth month, boolean approved, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        PreferenceSubmission submission = submissionRepository.findByEmployeeIdAndMonth(employeeId, month.atDay(1))
                .filter(s -> s.getEmployee().getStore().getId().equals(storeId) && s.getContractExceedApproved() != null)
                .orElseThrow(() -> new IllegalArgumentException("계약 상한을 넘긴 신청이 없습니다."));
        submission.decideContractExceed(approved);
        fcmService.send(submission.getEmployee().getUser(), approved ? "계약 초과 근무 승인" : "계약 초과 근무 거절",
                month + " 근무를 계약보다 많이 신청한 건이 " + (approved ? "승인되었어요." : "거절되어 계약 시간까지만 배정돼요."));
    }

    // 지난달 제출 내용을 이번 달 칸에 옮겨 담은 초안. 저장하지 않고 돌려준다 (제출은 마지막 단계에서)
    // 칸은 지난달 같은 요일·교대에 마지막으로 고른 값을 쓰고, 이번 달 잠긴 칸은 비운다
    public MonthView draftFromPrevious(Long storeId, YearMonth month, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        YearMonth previous = month.minusMonths(1);
        PreferenceSubmission last = submissionRepository.findByEmployeeIdAndMonth(employee.getId(), previous.atDay(1))
                .orElseThrow(() -> new IllegalArgumentException(previous + "에 제출한 희망이 없습니다."));
        Map<String, PreferenceLevel> byPattern = new HashMap<>();
        preferenceRepository.findByEmployeeIdAndDateBetween(employee.getId(), previous.atDay(1), previous.atEndOfMonth()).stream()
                .sorted(Comparator.comparing(ShiftPreference::getDate))
                .forEach(p -> byPattern.put(p.getDate().getDayOfWeek() + "|" + p.getShift().getId(), p.getLevel()));
        List<Shift> shifts = sortedShifts(storeId);
        List<Cell> cells = buildCells(employee, month, shifts).stream()
                .map(c -> c.locked() ? c : new Cell(c.date(), c.shift(),
                        byPattern.get(c.date().getDayOfWeek() + "|" + c.shift().getId()), false, null))
                .toList();
        PreferenceSubmission draft = new PreferenceSubmission(employee, month.atDay(1));
        draft.update(last.getMaxWeekendShifts(), last.getMinMonthlyHours(), last.getMaxMonthlyHours(),
                last.getMaxConsecutiveDays(), last.isNoOpenAfterClose(), last.getPriorityList());
        draft.updateFullTimeOptions(last.getShiftAttitudeMap(), last.isRestDaysTogether());
        return new MonthView(employee, month, deadlineOf(employee.getStore(), month), shifts, cells, draft);
    }

    // 이번 달 근무량 화면 (W06). min/max를 주면 그 값으로 예상 급여를 계산한다
    public Workload estimateWorkload(Long storeId, YearMonth month, Integer minMonthly, Integer maxMonthly, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        int wage = employee.getHourlyWage() != null ? employee.getHourlyWage() : 0;
        Integer contractMin = contractMonthly(employee.getContractMinWeeklyHours(), month);
        Integer contractMax = contractMonthly(employee.getContractMaxWeeklyHours(), month);
        Integer min = minMonthly != null ? minMonthly : contractMin;
        Integer max = maxMonthly != null ? maxMonthly : contractMax;
        double weeks = month.lengthOfMonth() / 7.0;
        return new Workload(employee.getContractMinWeeklyHours(), employee.getContractMaxWeeklyHours(), contractMin, contractMax,
                wage, min, max,
                min != null ? monthlyPay(min, wage, weeks) : null,
                max != null ? monthlyPay(max, wage, weeks) : null,
                min != null && min / weeks < WEEKLY_HOLIDAY_THRESHOLD_HOURS,
                contractMax != null && max != null && max > contractMax);
    }

    // 정규직 연차·휴무 화면 (E01): 이번 달 휴무 신청 중 연차 1일·반차 0.5일을 대안으로 둔 만큼 최대 사용
    public LeavePlan planLeave(Long storeId, YearMonth month, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        LeaveService.Balance balance = leaveService.balanceOf(employee);
        double maxUse = dayOffRequestRepository.findByEmployeeIdOrderByDateAsc(employee.getId()).stream()
                .filter(r -> YearMonth.from(r.getDate()).equals(month))
                .mapToDouble(r -> r.getFallbackList().contains(DayOffFallback.ANNUAL_LEAVE) ? 1.0
                        : r.getFallbackList().contains(DayOffFallback.HALF_DAY) ? 0.5 : 0.0)
                .sum();
        return new LeavePlan(balance != null ? balance.remaining() : null, maxUse,
                balance != null ? balance.remaining() - maxUse : null);
    }

    public List<UnavailablePeriod> findMyPeriods(Long storeId, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        return periodRepository.findByEmployeeIdOrderByStartDateAsc(employee.getId());
    }

    // "이번 달만 예외" 기간을 통째로 교체
    @Transactional
    public List<UnavailablePeriod> replaceMyPeriods(Long storeId, List<PeriodInput> inputs, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        List<UnavailablePeriod> rows = new ArrayList<>();
        for (PeriodInput input : inputs) {
            if (input.startDate() == null || input.endDate() == null || input.endDate().isBefore(input.startDate())) {
                throw new IllegalArgumentException("예외 기간의 시작·끝 날짜를 확인해주세요.");
            }
            rows.add(new UnavailablePeriod(employee, input.startDate(), input.endDate(), input.weekdaysOnly(), input.note()));
        }
        periodRepository.deleteAll(periodRepository.findByEmployeeIdOrderByStartDateAsc(employee.getId()));
        periodRepository.flush();
        return periodRepository.saveAll(rows);
    }

    // 다음 달 희망 마감 7일 전·1일 전에 아직 내지 않은 직원에게 알린다. 알린 인원 수를 돌려준다
    @Transactional
    public int remindDeadlines(LocalDate today) {
        int reminded = 0;
        for (Store store : storeRepository.findAll()) {
            YearMonth next = YearMonth.from(today).plusMonths(1);
            LocalDate deadline = deadlineOf(store, next);
            long daysLeft = ChronoUnit.DAYS.between(today, deadline);
            if (daysLeft != 7 && daysLeft != 1) {
                continue;
            }
            List<Long> submitted = submissionRepository.findByEmployee_StoreIdAndMonth(store.getId(), next.atDay(1)).stream()
                    .map(s -> s.getEmployee().getId())
                    .toList();
            for (Employee employee : employeeRepository.findByStoreIdAndStatus(store.getId(), EmployeeStatus.APPROVED)) {
                if (!submitted.contains(employee.getId())) {
                    fcmService.send(employee.getUser(), next.getMonthValue() + "월 희망 제출 마감 D-" + daysLeft,
                            "지난달 답을 불러오면 1분이면 끝나요");
                    reminded++;
                }
            }
        }
        return reminded;
    }

    // 계약 주 시간을 그달 시간으로 (주 수 = 일수 / 7)
    public static Integer contractMonthly(Integer weeklyHours, YearMonth month) {
        return weeklyHours == null ? null : (int) Math.round(weeklyHours * month.lengthOfMonth() / 7.0);
    }

    // 기본급 + 주휴수당(주 15시간 이상이면 주 근무시간/5, 최대 8시간)
    private static long monthlyPay(int monthlyHours, int wage, double weeks) {
        double weekly = monthlyHours / weeks;
        double holidayHours = weekly >= WEEKLY_HOLIDAY_THRESHOLD_HOURS ? Math.min(weekly / 5, 8) * weeks : 0;
        return Math.round((monthlyHours + holidayHours) * wage);
    }

    // 다음 달 희망은 이번 달 마감일까지 받는다. 월간 자동 생성을 켰으면 생성 3일 전까지
    public static LocalDate deadlineOf(Store store, YearMonth month) {
        if (store.isAutoGenerate() && store.getScheduleCycleOrDefault() == ScheduleCycle.MONTHLY) {
            return month.atDay(1).minusDays(store.getAutoGenerateDaysBeforeOrDefault())
                    .minusDays(SchedulePlanService.DEADLINE_DAYS_BEFORE_GENERATION);
        }
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
        List<UnavailablePeriod> periods = periodRepository.findByEmployeeIdOrderByStartDateAsc(employee.getId());
        Map<String, PreferenceLevel> levelByKey = new HashMap<>();
        preferenceRepository.findByEmployeeIdAndDateBetween(employee.getId(), month.atDay(1), month.atEndOfMonth())
                .forEach(p -> levelByKey.put(cellKey(p.getDate(), p.getShift().getId()), p.getLevel()));

        List<Cell> cells = new ArrayList<>();
        for (LocalDate date = month.atDay(1); !date.isAfter(month.atEndOfMonth()); date = date.plusDays(1)) {
            for (Shift shift : shifts) {
                if (plan.of(shift, date).target() == 0) {
                    continue;
                }
                Optional<String> lock = lockOf(employee, fixed, periods, shift, date);
                cells.add(new Cell(date, shift, lock.isPresent() ? null : levelByKey.get(cellKey(date, shift.getId())),
                        lock.isPresent(), lock.orElse(null)));
            }
        }
        return cells;
    }

    static Optional<String> lockOf(Employee employee, List<FixedSchedule> fixed, List<UnavailablePeriod> periods, Shift shift,
                                   LocalDate date) {
        if (employee.isMinor() && ScheduleAutomationService.isRestrictedForMinor(shift.getStartTime(), shift.getEndTime())) {
            return Optional.of("미성년자 근무 제한");
        }
        Optional<String> period = periods.stream()
                .filter(p -> p.covers(date))
                .map(p -> p.getNote() != null && !p.getNote().isBlank() ? p.getNote() : "이번 달 예외")
                .findFirst();
        if (period.isPresent()) {
            return period;
        }
        return fixed.stream()
                .filter(f -> f.appliesOn(date) && f.overlaps(shift.getStartTime(), shift.getEndTime()))
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
