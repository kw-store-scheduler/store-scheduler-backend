package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.DayOffFallback;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.FixedSchedule;
import com.example.store_scheduler_backend.domain.PreferenceLevel;
import com.example.store_scheduler_backend.domain.PreferencePriority;
import com.example.store_scheduler_backend.domain.PreferenceSubmission;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ScheduleProposal;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftPreference;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Assignment;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Backup;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Candidate;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.DayOffViolation;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.EmployeeSummary;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Metrics;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Notice;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Shortage;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.FixedScheduleRepository;
import com.example.store_scheduler_backend.repository.PreferenceSubmissionRepository;
import com.example.store_scheduler_backend.repository.ScheduleProposalRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftPreferenceRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.ShiftSkillRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

// 엔진은 직원을 "emp_{id}", 날짜를 기간 시작일 기준 인덱스(0=시작일), 교대를 shifts 배열 인덱스로 다룬다
@Service
@RequiredArgsConstructor
public class ScheduleAutomationService {

    private static final int DAYS_IN_WEEK = 7;
    private static final String EMPLOYEE_KEY_PREFIX = "emp_";
    private static final String SKILL_KEY_PREFIX = "skill_";
    private static final int SOLVER_TIME_LIMIT_SECONDS = 10;
    private static final int MONTHLY_SOLVER_TIME_LIMIT_SECONDS = 20;
    private static final double NIGHT_BONUS_RATE = 0.5;
    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(12, 0);
    // 가용 시간은 자정을 넘겨 등록할 수 없어서, 23:59까지 가능하면 자정 넘김 교대도 가능한 것으로 본다
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59);
    private static final int MAX_BACKUPS = 3;
    private static final int FULL_TIME_MUST_OFF_WEIGHT = 9_000_000;
    private static final int MINOR_MAX_SHIFT_MINUTES = 7 * 60;
    private static final int MAX_WEEKLY_HOURS = 52;
    private static final int MAX_CONSECUTIVE_DAYS = 4;
    private static final int MAX_WARNINGS = 30;
    // 우선순위 1~5위의 패널티 배율. 순위에 없는 항목은 1배
    private static final double[] PRIORITY_SCALES = {2.0, 1.6, 1.3, 1.1, 1.0};

    // 같은 입력을 가중치만 바꿔 다시 풀어 성격이 다른 후보안을 만든다. 키는 엔진의 weight_overrides 이름
    private record Plan(String label, String description, Map<String, Object> weightOverrides) {
    }

    private static final List<Plan> PLANS = List.of(
            new Plan("공정 우선", "인건비가 늘더라도 직원 간 근무일과 주말 근무 차이를 최소화",
                    Map.of("FAIRNESS_W", 1_000_000, "HOLIDAY_W", 1_000_000)),
            new Plan("인건비 우선", "근무일 균등·주 15시간·기존 근무 유지보다 인건비 절감을 우선",
                    Map.of("FAIRNESS_W", 0, "LOW_HOURS_W", 0, "CHANGE_W", 0)),
            new Plan("희망 우선", "직원이 제출한 선호 칸·희망 휴무·개인 근무 규칙을 인건비보다 우선",
                    Map.of("PREF_W", 1_000_000, "PREFER_OFF_W", 2_000_000, "WEEKEND_W", 2_000_000,
                            "CONSEC_W", 2_000_000, "RHYTHM_W", 2_000_000, "HOURS_W", 200_000)));

    private record LabeledSolution(String label, String description, List<Map<String, Object>> solution) {
    }

    // 근무표를 만들 기간의 매장 데이터. 엔진 입력, 배정 사유, 후보안 지표 계산에 같이 쓴다
    private record PeriodInput(Store store, LocalDate start, int numDays, List<Employee> employees, List<Shift> shifts,
                               List<Schedule> current, List<DayOffRequest> dayOffs,
                               List<ShiftSkillRequirement> skillRequirements,
                               Map<Long, Map<Integer, Availability>> availabilityByEmployee,
                               Map<Long, List<ShiftPreference>> preferencesByEmployee, Set<Long> preferenceEmployees,
                               Map<Long, List<FixedSchedule>> fixedByEmployee,
                               Map<Long, PreferenceSubmission> submissionByEmployee,
                               StaffingPlanService.Plan staffing) {
        LocalDate end() {
            return start.plusDays(numDays - 1);
        }

        LocalDate dateOf(int day) {
            return start.plusDays(day);
        }

        int dayOf(LocalDate date) {
            return (int) ChronoUnit.DAYS.between(start, date);
        }

        boolean monthly() {
            return numDays > DAYS_IN_WEEK;
        }

        // 그 기간에 희망을 제출했거나 칸을 고른 직원은 요일별 가용 시간 대신 제출한 칸으로 근무 가능 여부를 정한다
        boolean usesPreferences(Long employeeId) {
            return preferenceEmployees.contains(employeeId);
        }

        PreferenceLevel levelOf(Long employeeId, LocalDate date, LocalTime start, LocalTime end) {
            return preferencesByEmployee.getOrDefault(employeeId, List.of()).stream()
                    .filter(p -> p.getDate().equals(date)
                            && p.getShift().getStartTime().equals(start) && p.getShift().getEndTime().equals(end))
                    .map(ShiftPreference::getLevel)
                    .findFirst()
                    .orElse(null);
        }

        boolean hasPreferredOn(Long employeeId, LocalDate date) {
            return preferencesByEmployee.getOrDefault(employeeId, List.of()).stream()
                    .anyMatch(p -> p.getDate().equals(date) && p.getLevel() == PreferenceLevel.PREFERRED);
        }
    }

    private final ScheduleEngine scheduleEngine;
    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final AvailabilityRepository availabilityRepository;
    private final ShiftRepository shiftRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleProposalRepository proposalRepository;
    private final ShiftSkillRequirementRepository requirementRepository;
    private final DayOffRequestRepository dayOffRequestRepository;
    private final ShiftPreferenceRepository preferenceRepository;
    private final PreferenceSubmissionRepository submissionRepository;
    private final FixedScheduleRepository fixedScheduleRepository;
    private final StaffingPlanService staffingPlanService;
    private final ObjectMapper objectMapper;
    private final SubstituteFinder substituteFinder;
    private final FcmService fcmService;

    // weekStart(월요일)부터 7일간의 근무표 후보안을 만든다. 확정 전까지 근무표는 바뀌지 않는다.
    @Transactional
    public ScheduleProposalResponse generateProposal(Long storeId, LocalDate weekStart, String managerUsername) {
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            storeAccessGuard.verifyOwner(storeId, managerUsername);
            throw new IllegalArgumentException("근무표 시작일(weekStart)은 월요일이어야 합니다.");
        }
        return generate(storeId, weekStart, DAYS_IN_WEEK, managerUsername);
    }

    // 한 달 치 근무표 후보안. 직원이 제출한 개인 근무 규칙(주말 횟수, 근무량, 연속 근무, 근무 리듬)은 월간 생성에만 쓴다
    @Transactional
    public ScheduleProposalResponse generateMonthlyProposal(Long storeId, YearMonth month, String managerUsername) {
        return generate(storeId, month.atDay(1), month.lengthOfMonth(), managerUsername);
    }

    private ScheduleProposalResponse generate(Long storeId, LocalDate start, int numDays, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        List<Employee> employees = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
        List<Shift> shifts = shiftRepository.findByStoreId(storeId).stream()
                .sorted(Comparator.comparing(Shift::getStartTime).thenComparing(Shift::getId))
                .toList();
        if (employees.isEmpty() || shifts.isEmpty()) {
            throw new IllegalArgumentException("승인된 직원 또는 등록된 근무 시간대(Shift)가 없습니다.");
        }

        PeriodInput input = loadPeriod(store, start, numDays, employees, shifts);
        Map<String, Object> config = buildEngineConfig(input);
        addOffRequests(config, input);
        config.put("prev_assignments", toPrevAssignments(input));

        // 인원·스킬 부족과 상태는 첫 번째 기준(공정 우선) 기준. 이 해가 없으면 다른 기준으로도 찾을 수 없다
        Map<String, Object> result = scheduleEngine.run(withPlan(config, PLANS.get(0)));
        String status = (String) result.get("status");
        if (result.get("schedule") == null) {
            return ScheduleProposalResponse.failed(status, start, input.end());
        }
        long solveMs = solveMsOf(result);
        List<LabeledSolution> solutions = new ArrayList<>();
        solutions.add(new LabeledSolution(PLANS.get(0).label(), PLANS.get(0).description(), toSolution(result.get("schedule"))));
        for (Plan plan : PLANS.subList(1, PLANS.size())) {
            Map<String, Object> planResult = scheduleEngine.run(withPlan(config, plan));
            solveMs += solveMsOf(planResult);
            if (planResult.get("schedule") != null) {
                solutions.add(new LabeledSolution(plan.label(), plan.description(), toSolution(planResult.get("schedule"))));
            }
        }
        List<Candidate> candidates = toCandidates(solutions, input);
        List<Shortage> shortages = new ArrayList<>(toShortages(result.get("shortage_report"), input));
        shortages.addAll(toSkillShortages(result.get("skill_shortage_report"), input));

        ScheduleProposal proposal = proposalRepository.save(new ScheduleProposal(store, start, numDays, status, solveMs,
                objectMapper.writeValueAsString(candidates), objectMapper.writeValueAsString(shortages)));

        Notice notice = ScheduleProposalResponse.noticeFor(candidates, shortages);
        if (notice.needsAttention()) {
            String period = input.monthly() ? YearMonth.from(start) + " 근무표: " : start + " 주 근무표: ";
            fcmService.sendToDevice(store.getOwner().getDeviceToken(), "근무표 후보안 확인 필요", period + notice.summary());
        }
        return new ScheduleProposalResponse(proposal.getId(), true, status,
                ScheduleProposalResponse.messageFor(candidates.size(), shortages.size()),
                start, solveMs, proposal.getStatus(), null, shortages, candidates, notice, input.end());
    }

    private PeriodInput loadPeriod(Store store, LocalDate start, int numDays, List<Employee> employees, List<Shift> shifts) {
        Long storeId = store.getId();
        LocalDate end = start.plusDays(numDays - 1);
        Map<Long, Map<Integer, Availability>> availabilityByEmployee = availabilityRepository.findByEmployee_StoreId(storeId).stream()
                .collect(Collectors.groupingBy(
                        a -> a.getEmployee().getId(),
                        Collectors.toMap(this::toDayIndex, Function.identity())));
        Map<Long, List<ShiftPreference>> preferencesByEmployee = preferenceRepository
                .findByEmployee_StoreIdAndDateBetween(storeId, start, end).stream()
                .collect(Collectors.groupingBy(p -> p.getEmployee().getId()));

        Set<Long> preferenceEmployees = new HashSet<>(preferencesByEmployee.keySet());
        Map<Long, PreferenceSubmission> submissionByEmployee = new HashMap<>();
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            for (PreferenceSubmission submission : submissionRepository.findByEmployee_StoreIdAndMonth(storeId, m.atDay(1))) {
                preferenceEmployees.add(submission.getEmployee().getId());
                if (m.equals(YearMonth.from(start))) {
                    submissionByEmployee.put(submission.getEmployee().getId(), submission);
                }
            }
        }

        return new PeriodInput(store, start, numDays, employees, shifts,
                scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, start, end),
                dayOffRequestRepository.findByEmployee_StoreIdAndDateBetweenOrderByDateAsc(storeId, start, end),
                requirementRepository.findByShift_Store_Id(storeId),
                availabilityByEmployee, preferencesByEmployee, preferenceEmployees,
                fixedScheduleRepository.findByEmployee_StoreId(storeId).stream()
                        .collect(Collectors.groupingBy(f -> f.getEmployee().getId())),
                submissionByEmployee,
                staffingPlanService.planFor(storeId, start, end));
    }

    // 기준마다 대표 해 하나만 쓰므로 동점 대안 탐색(max_alt_solutions)은 하지 않는다
    private Map<String, Object> withPlan(Map<String, Object> config, Plan plan) {
        Map<String, Object> planConfig = new HashMap<>(config);
        planConfig.put("weight_overrides", plan.weightOverrides());
        planConfig.put("max_alt_solutions", 1);
        return planConfig;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toSolution(Object schedule) {
        return (List<Map<String, Object>>) schedule;
    }

    private Map<String, Object> buildEngineConfig(PeriodInput input) {
        List<Shift> shifts = input.shifts();
        List<Integer> allShiftIndices = new ArrayList<>();
        for (int i = 0; i < shifts.size(); i++) {
            allShiftIndices.add(i);
        }

        List<Map<String, Object>> employeeConfigs = new ArrayList<>();
        for (Employee employee : input.employees()) {
            Map<String, Object> config = new HashMap<>();
            config.put("id", employeeKey(employee));
            config.put("name", employee.getName());
            Set<Integer> availableDays = new TreeSet<>();
            Set<Map<String, Integer>> unavailable = new LinkedHashSet<>();
            if (input.usesPreferences(employee.getId())) {
                List<Map<String, Integer>> preferred = new ArrayList<>();
                Set<Map<String, Integer>> open = new HashSet<>();
                for (ShiftPreference p : input.preferencesByEmployee().getOrDefault(employee.getId(), List.of())) {
                    int shiftIndex = indexOfShiftId(shifts, p.getShift().getId());
                    if (shiftIndex < 0) {
                        continue;
                    }
                    Map<String, Integer> slot = slot(input.dayOf(p.getDate()), shiftIndex);
                    availableDays.add(input.dayOf(p.getDate()));
                    open.add(slot);
                    if (p.getLevel() == PreferenceLevel.PREFERRED) {
                        preferred.add(slot);
                    }
                }
                for (int day : availableDays) {
                    for (int s = 0; s < shifts.size(); s++) {
                        if (!open.contains(slot(day, s))) {
                            unavailable.add(slot(day, s));
                        }
                    }
                }
                config.put("preferred_slots", preferred);
            } else {
                unavailable.addAll(unavailableByAvailability(input, employee, availableDays));
                config.put("preferred_shifts", allShiftIndices);
            }
            unavailable.addAll(fixedScheduleSlots(input, employee, availableDays));
            if (employee.isMinor()) {
                unavailable.addAll(minorRestrictedSlots(shifts, input.numDays()));
            }
            config.put("available_days", new ArrayList<>(availableDays));
            config.put("unavailable_slots", new ArrayList<>(unavailable));
            config.put("skills", employee.getSkills().stream().map(sk -> SKILL_KEY_PREFIX + sk.getId()).toList());
            config.put("hourly", employee.getHourlyWage() != null ? employee.getHourlyWage() : 0);
            if (input.monthly()) {
                addPersonalRules(config, input.submissionByEmployee().get(employee.getId()));
            }
            employeeConfigs.add(config);
        }

        List<Map<String, Object>> shiftConfigs = new ArrayList<>();
        List<Integer> targetStaff = new ArrayList<>();
        List<Integer> minStaff = new ArrayList<>();
        for (Shift shift : shifts) {
            shiftConfigs.add(Map.of(
                    "name", shift.getName(),
                    "hours", shiftHours(shift.getStartTime(), shift.getEndTime()),
                    "is_night", isNightShift(shift.getStartTime(), shift.getEndTime())));
            targetStaff.add(shift.getRequiredStaff());
            minStaff.add(Math.min(1, shift.getRequiredStaff()));
        }
        List<List<Integer>> minByDay = new ArrayList<>();
        List<List<Integer>> targetByDay = new ArrayList<>();
        for (int day = 0; day < input.numDays(); day++) {
            List<Integer> mins = new ArrayList<>();
            List<Integer> targets = new ArrayList<>();
            for (Shift shift : shifts) {
                StaffingPlanService.Staffing staffing = input.staffing().of(shift, input.dateOf(day));
                mins.add(staffing.min());
                targets.add(staffing.target());
            }
            minByDay.add(mins);
            targetByDay.add(targets);
        }

        Map<String, Object> config = new HashMap<>();
        config.put("employees", employeeConfigs);
        config.put("shifts", shiftConfigs);
        config.put("target_staff", targetStaff);
        config.put("max_staff", targetStaff);
        config.put("min_staff", minStaff);
        config.put("min_staff_by_day", minByDay);
        config.put("target_staff_by_day", targetByDay);
        config.put("max_staff_by_day", targetByDay);
        config.put("base_hourly", averageHourlyWage(input.employees()));
        config.put("night_bonus", NIGHT_BONUS_RATE);
        config.put("num_days", input.numDays());
        config.put("weeks", calendarWeeks(input));
        List<Integer> weekendDays = weekendDays(input);
        config.put("weekend_days", weekendDays);
        // 주말 근무 횟수를 고르게 나누는 데 엔진의 공휴일 편차 항목을 쓴다
        config.put("holiday_indices", weekendDays);
        config.put("open_shifts", openShifts(shifts));
        config.put("close_shifts", closeShifts(shifts));
        if (input.store().isSplitShiftAllowed()) {
            config.put("max_shifts_per_day", 2);
            config.put("conflicting_shifts", conflictingShifts(shifts));
        }
        config.put("time_limit", input.monthly() ? MONTHLY_SOLVER_TIME_LIMIT_SECONDS : SOLVER_TIME_LIMIT_SECONDS);
        config.put("skill_requirements", toEngineSkillRequirements(input.skillRequirements(), shifts));
        return config;
    }

    // 가용 시간을 등록한 요일만 근무 가능. 그중 가용 시간 밖이거나 그 요일 전용이 아닌 교대는 불가
    private List<Map<String, Integer>> unavailableByAvailability(PeriodInput input, Employee employee, Set<Integer> availableDays) {
        Map<Integer, Availability> availabilityByDay = input.availabilityByEmployee().getOrDefault(employee.getId(), Map.of());
        List<Map<String, Integer>> slots = new ArrayList<>();
        for (int day = 0; day < input.numDays(); day++) {
            int weekday = input.dateOf(day).getDayOfWeek().getValue() - 1;
            Availability availability = availabilityByDay.get(weekday);
            if (availability == null) {
                continue;
            }
            availableDays.add(day);
            for (int s = 0; s < input.shifts().size(); s++) {
                Shift shift = input.shifts().get(s);
                if (!isShiftOpenOn(shift, weekday) || !fitsAvailability(shift.getStartTime(), shift.getEndTime(),
                        availability.getStartTime(), availability.getEndTime())) {
                    slots.add(slot(day, s));
                }
            }
        }
        return slots;
    }

    // 희망 제출 뒤에 고정 일정을 바꾼 경우에도 겹치는 칸은 근무 불가로 넘긴다
    private List<Map<String, Integer>> fixedScheduleSlots(PeriodInput input, Employee employee, Set<Integer> availableDays) {
        List<FixedSchedule> fixed = input.fixedByEmployee().getOrDefault(employee.getId(), List.of());
        List<Map<String, Integer>> slots = new ArrayList<>();
        for (int day : availableDays) {
            DayOfWeek weekday = input.dateOf(day).getDayOfWeek();
            for (int s = 0; s < input.shifts().size(); s++) {
                Shift shift = input.shifts().get(s);
                if (fixed.stream().anyMatch(f -> f.getDayOfWeek() == weekday && f.overlaps(shift.getStartTime(), shift.getEndTime()))) {
                    slots.add(slot(day, s));
                }
            }
        }
        return slots;
    }

    private List<Map<String, Integer>> minorRestrictedSlots(List<Shift> shifts, int numDays) {
        List<Map<String, Integer>> slots = new ArrayList<>();
        for (int s = 0; s < shifts.size(); s++) {
            if (isRestrictedForMinor(shifts.get(s).getStartTime(), shifts.get(s).getEndTime())) {
                for (int day = 0; day < numDays; day++) {
                    slots.add(slot(day, s));
                }
            }
        }
        return slots;
    }

    // 직원이 정한 우선순위는 해당 항목의 패널티 배율로 바꿔 넘긴다
    private void addPersonalRules(Map<String, Object> config, PreferenceSubmission submission) {
        if (submission == null) {
            return;
        }
        if (submission.getMaxWeekendShifts() != null) {
            config.put("max_weekend_shifts", submission.getMaxWeekendShifts());
        }
        if (submission.getMinMonthlyHours() != null) {
            config.put("min_total_hours", submission.getMinMonthlyHours());
        }
        if (submission.getMaxMonthlyHours() != null) {
            config.put("max_total_hours", submission.getMaxMonthlyHours());
        }
        if (submission.getMaxConsecutiveDays() != null) {
            config.put("max_consecutive_days", submission.getMaxConsecutiveDays());
        }
        if (submission.isNoOpenAfterClose()) {
            config.put("no_open_after_close", true);
        }
        List<PreferencePriority> priorities = submission.getPriorityList();
        Map<String, Double> scale = new HashMap<>();
        for (int rank = 0; rank < priorities.size() && rank < PRIORITY_SCALES.length; rank++) {
            for (String weight : engineWeightsOf(priorities.get(rank))) {
                scale.put(weight, PRIORITY_SCALES[rank]);
            }
        }
        if (!scale.isEmpty()) {
            config.put("weight_scale", scale);
        }
    }

    private static List<String> engineWeightsOf(PreferencePriority priority) {
        return switch (priority) {
            case DAY_OFF -> List.of("PREFER_OFF_W");
            case SHIFT -> List.of("PREF_W");
            case WEEKEND -> List.of("WEEKEND_W");
            case HOURS -> List.of("HOURS_W");
            case RHYTHM -> List.of("RHYTHM_W", "CONSEC_W");
        };
    }

    private List<List<Integer>> calendarWeeks(PeriodInput input) {
        Map<LocalDate, List<Integer>> byWeek = new LinkedHashMap<>();
        for (int day = 0; day < input.numDays(); day++) {
            byWeek.computeIfAbsent(input.dateOf(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    k -> new ArrayList<>()).add(day);
        }
        return new ArrayList<>(byWeek.values());
    }

    private List<Integer> weekendDays(PeriodInput input) {
        List<Integer> days = new ArrayList<>();
        for (int day = 0; day < input.numDays(); day++) {
            if (PreferenceService.isWeekend(input.dateOf(day))) {
                days.add(day);
            }
        }
        return days;
    }

    // 가장 일찍 시작하는 교대를 오픈, 가장 늦게 끝나는 교대를 마감으로 본다 (교대가 하나면 둘 다 없음)
    private static List<Integer> openShifts(List<Shift> shifts) {
        if (shifts.size() < 2) {
            return List.of();
        }
        LocalTime earliest = shifts.get(0).getStartTime();
        List<Integer> result = new ArrayList<>();
        for (int s = 0; s < shifts.size(); s++) {
            if (shifts.get(s).getStartTime().equals(earliest)) {
                result.add(s);
            }
        }
        return result;
    }

    private static List<Integer> closeShifts(List<Shift> shifts) {
        if (shifts.size() < 2) {
            return List.of();
        }
        int latest = shifts.stream().mapToInt(ScheduleAutomationService::endMinutes).max().orElseThrow();
        List<Integer> result = new ArrayList<>();
        for (int s = 0; s < shifts.size(); s++) {
            if (endMinutes(shifts.get(s)) == latest) {
                result.add(s);
            }
        }
        return result;
    }

    // 자정을 넘기는 교대는 다음 날 시각으로 계산한다
    static int endMinutes(Shift shift) {
        int end = shift.getEndTime().toSecondOfDay() / 60;
        return shift.getEndTime().isAfter(shift.getStartTime()) ? end : end + 24 * 60;
    }

    private static List<List<Integer>> conflictingShifts(List<Shift> shifts) {
        List<List<Integer>> pairs = new ArrayList<>();
        for (int a = 0; a < shifts.size(); a++) {
            for (int b = a + 1; b < shifts.size(); b++) {
                int aStart = shifts.get(a).getStartTime().toSecondOfDay() / 60;
                int bStart = shifts.get(b).getStartTime().toSecondOfDay() / 60;
                if (aStart < endMinutes(shifts.get(b)) && bStart < endMinutes(shifts.get(a))) {
                    pairs.add(List.of(a, b));
                }
            }
        }
        return pairs;
    }

    // 정규직 필수 휴무는 알바(엔진 기본 1천만)보다 낮게 넘겨, 인원을 채우려고 휴무를 깨야 하면 정규직 휴무를 먼저 깬다
    private Map<String, Object> toEngineOffRequest(DayOffRequest request, PeriodInput input) {
        Map<String, Object> offRequest = new HashMap<>();
        offRequest.put("day", input.dayOf(request.getDate()));
        offRequest.put("type", request.getType() == DayOffType.MUST ? "must" : "prefer");
        if (request.getType() == DayOffType.MUST && request.getEmployee().getEmploymentType() == EmploymentType.FULL_TIME) {
            offRequest.put("weight", FULL_TIME_MUST_OFF_WEIGHT);
        }
        return offRequest;
    }

    // 희망 휴무의 대안이 "오후만 쉬기"면 오후 교대에 휴무 요청을 한 번 더 걸어, 그날 나와야 하면 오전 교대로 보낸다
    private List<Map<String, Object>> toEngineOffRequests(DayOffRequest request, PeriodInput input) {
        List<Map<String, Object>> requests = new ArrayList<>();
        requests.add(toEngineOffRequest(request, input));
        if (request.getType() == DayOffType.PREFER && request.getFallbackList().contains(DayOffFallback.AFTERNOON_OFF)) {
            List<Integer> afternoon = new ArrayList<>();
            for (int s = 0; s < input.shifts().size(); s++) {
                if (!input.shifts().get(s).getStartTime().isBefore(AFTERNOON_START)) {
                    afternoon.add(s);
                }
            }
            if (!afternoon.isEmpty() && afternoon.size() < input.shifts().size()) {
                requests.add(Map.of("day", input.dayOf(request.getDate()), "type", "prefer", "shifts", afternoon));
            }
        }
        return requests;
    }

    // 필수 휴무 보장이 켜져 있으면 그날의 모든 교대를 근무 불가(하드 제약)로도 넘긴다
    @SuppressWarnings("unchecked")
    private void addOffRequests(Map<String, Object> config, PeriodInput input) {
        Map<Long, List<DayOffRequest>> byEmployee = input.dayOffs().stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId()));
        int shiftCount = input.shifts().size();
        for (Map<String, Object> employee : (List<Map<String, Object>>) config.get("employees")) {
            Long id = Long.valueOf(((String) employee.get("id")).substring(EMPLOYEE_KEY_PREFIX.length()));
            List<DayOffRequest> requests = byEmployee.getOrDefault(id, List.of());
            employee.put("off_requests", requests.stream().flatMap(r -> toEngineOffRequests(r, input).stream()).toList());
            if (input.store().isMustDayOffGuaranteed()) {
                List<Map<String, Integer>> slots = new ArrayList<>((List<Map<String, Integer>>) employee.get("unavailable_slots"));
                requests.stream()
                        .filter(r -> r.getType() == DayOffType.MUST)
                        .forEach(r -> {
                            for (int s = 0; s < shiftCount; s++) {
                                slots.add(slot(input.dayOf(r.getDate()), s));
                            }
                        });
                employee.put("unavailable_slots", slots);
            }
        }
    }

    // 스킬 이름은 바뀔 수 있어서 엔진에는 id로 넘긴다
    private List<Map<String, Object>> toEngineSkillRequirements(List<ShiftSkillRequirement> skillRequirements, List<Shift> shifts) {
        List<Map<String, Object>> requirements = new ArrayList<>();
        for (ShiftSkillRequirement r : skillRequirements) {
            int shiftIndex = indexOfShiftId(shifts, r.getShift().getId());
            if (shiftIndex >= 0) {
                requirements.add(Map.of("shift", shiftIndex, "skill", SKILL_KEY_PREFIX + r.getSkill().getId(),
                        "count", r.getRequiredCount()));
            }
        }
        return requirements;
    }

    // 삭제된 교대나 승인 취소된 직원의 근무처럼 현재 설정과 맞지 않는 건은 제외
    private List<Map<String, Object>> toPrevAssignments(PeriodInput input) {
        Set<Long> employeeIds = input.employees().stream().map(Employee::getId).collect(Collectors.toSet());
        List<Map<String, Object>> prev = new ArrayList<>();
        for (Schedule schedule : input.current()) {
            int shiftIndex = indexOfShift(input.shifts(), schedule.getStartTime(), schedule.getEndTime());
            if (shiftIndex < 0 || !employeeIds.contains(schedule.getEmployee().getId())) {
                continue;
            }
            prev.add(Map.of(
                    "employee_id", employeeKey(schedule.getEmployee()),
                    "day", input.dayOf(schedule.getWorkDate()),
                    "shift", shiftIndex));
        }
        return prev;
    }

    // 기준별 해를 순서대로 후보안으로 만든다. 배치가 같은 해는 하나로 합치고 라벨만 이어 붙인다
    private List<Candidate> toCandidates(List<LabeledSolution> solutions, PeriodInput input) {
        List<Employee> employees = input.employees();

        Map<String, Employee> employeeByKey = employees.stream()
                .collect(Collectors.toMap(this::employeeKey, Function.identity()));
        Set<String> currentSlots = input.current().stream()
                .map(s -> slotKey(s.getEmployee().getId(), s.getWorkDate(), s.getStartTime()))
                .collect(Collectors.toSet());

        List<Candidate> candidates = new ArrayList<>();
        List<Set<String>> slotsByCandidate = new ArrayList<>();
        for (LabeledSolution solution : solutions) {
            List<Assignment> assignments = withReasons(toAssignments(solution.solution(), input, employeeByKey),
                    currentSlots, input);
            Set<String> slots = assignments.stream()
                    .map(a -> slotKey(a.employeeId(), a.workDate(), a.startTime()))
                    .collect(Collectors.toSet());
            int same = slotsByCandidate.indexOf(slots);
            if (same >= 0) {
                Candidate c = candidates.get(same);
                candidates.set(same, new Candidate(c.index(), c.label() + " · " + solution.label(), c.description(),
                        c.estimatedCost(), c.changedFromCurrent(), c.employees(), c.assignments(), c.dayOffViolations(),
                        c.metrics()));
                continue;
            }
            slotsByCandidate.add(slots);
            candidates.add(new Candidate(candidates.size(), solution.label(), solution.description(),
                    estimateCost(assignments, employees), countChanges(assignments, currentSlots),
                    summarize(assignments, employees), assignments, findDayOffViolations(assignments, input),
                    computeMetrics(assignments, input)));
        }
        return candidates;
    }

    @SuppressWarnings("unchecked")
    private List<Assignment> toAssignments(List<Map<String, Object>> solution, PeriodInput input,
                                           Map<String, Employee> employeeByKey) {
        List<Assignment> assignments = new ArrayList<>();
        for (Map<String, Object> entry : solution) {
            String key = (String) entry.get("employee_id");
            Employee employee = employeeByKey.get(key);
            if (employee == null) {
                throw new IllegalStateException("엔진 결과에 매장 소속이 아닌 직원이 포함되어 있습니다: " + key);
            }
            for (Map<String, Object> slot : (List<Map<String, Object>>) entry.get("assignments")) {
                Shift shift = input.shifts().get(toInt(slot.get("shift")));
                assignments.add(new Assignment(employee.getId(), employee.getName(),
                        input.dateOf(toInt(slot.get("day"))), shift.getName(),
                        shift.getStartTime(), shift.getEndTime(), List.of()));
            }
        }
        assignments.sort(Comparator.comparing(Assignment::workDate).thenComparing(Assignment::startTime));
        return assignments;
    }

    private List<Assignment> withReasons(List<Assignment> assignments, Set<String> currentSlots, PeriodInput input) {
        Map<Long, Employee> employeeById = input.employees().stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        Map<String, DayOffType> dayOffBySlot = input.dayOffs().stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId() + "|" + r.getDate(), DayOffRequest::getType));
        Map<Long, Integer> hoursByEmployee = new HashMap<>();
        for (Assignment a : assignments) {
            hoursByEmployee.merge(a.employeeId(), shiftHours(a.startTime(), a.endTime()), Integer::sum);
        }
        long averageHours = Math.round(hoursByEmployee.values().stream().mapToInt(Integer::intValue).sum()
                / (double) input.employees().size());
        String period = input.monthly() ? "이번 달 " : "이번 주 ";

        List<Assignment> result = new ArrayList<>();
        for (Assignment a : assignments) {
            Employee employee = employeeById.get(a.employeeId());
            List<String> reasons = new ArrayList<>();
            if (currentSlots.contains(slotKey(a.employeeId(), a.workDate(), a.startTime()))) {
                reasons.add("기존 근무표 유지");
            }
            List<String> coveredSkills = requiredSkillsHeldBy(employee, a, input.skillRequirements());
            if (!coveredSkills.isEmpty()) {
                reasons.add("필요 스킬 보유: " + String.join(", ", coveredSkills));
            }
            DayOffType dayOff = dayOffBySlot.get(a.employeeId() + "|" + a.workDate());
            if (dayOff != null) {
                reasons.add((dayOff == DayOffType.MUST ? "필수" : "희망") + " 휴무 신청일이지만 배정됨");
            }
            if (input.usesPreferences(a.employeeId())) {
                PreferenceLevel level = input.levelOf(a.employeeId(), a.workDate(), a.startTime(), a.endTime());
                if (level == PreferenceLevel.PREFERRED) {
                    reasons.add("선호한 칸");
                } else if (input.hasPreferredOn(a.employeeId(), a.workDate())) {
                    reasons.add("그날 다른 교대를 선호했지만 인원·스킬 조건으로 가능 칸에 배정");
                } else {
                    reasons.add("가능하다고 한 칸");
                }
            } else {
                Availability availability = input.availabilityByEmployee()
                        .getOrDefault(a.employeeId(), Map.of())
                        .get(a.workDate().getDayOfWeek().getValue() - 1);
                if (availability != null) {
                    reasons.add("가용 시간 " + availability.getStartTime() + "~" + availability.getEndTime());
                }
            }
            reasons.add(period + hoursByEmployee.get(a.employeeId()) + "시간 (직원 평균 " + averageHours + "시간)");
            result.add(new Assignment(a.employeeId(), a.employeeName(), a.workDate(), a.shiftName(),
                    a.startTime(), a.endTime(), reasons));
        }
        return result;
    }

    private List<String> requiredSkillsHeldBy(Employee employee, Assignment assignment,
                                              List<ShiftSkillRequirement> skillRequirements) {
        int dayIndex = assignment.workDate().getDayOfWeek().getValue() - 1;
        return skillRequirements.stream()
                .filter(r -> r.getShift().getStartTime().equals(assignment.startTime())
                        && r.getShift().getEndTime().equals(assignment.endTime())
                        && isShiftOpenOn(r.getShift(), dayIndex))
                .filter(r -> employee.getSkills().stream().anyMatch(sk -> sk.getId().equals(r.getSkill().getId())))
                .map(r -> r.getSkill().getName())
                .distinct()
                .sorted()
                .toList();
    }

    // 엔진 비용은 평균 시급 기준이라, 후보안 비교에는 직원별 시급으로 다시 계산한 값을 쓴다
    private long estimateCost(List<Assignment> assignments, List<Employee> employees) {
        Map<Long, Integer> wageById = employees.stream()
                .collect(Collectors.toMap(Employee::getId, e -> e.getHourlyWage() != null ? e.getHourlyWage() : 0));
        long total = 0;
        for (Assignment a : assignments) {
            long base = (long) shiftHours(a.startTime(), a.endTime()) * wageById.get(a.employeeId());
            total += isNightShift(a.startTime(), a.endTime()) ? Math.round(base * (1 + NIGHT_BONUS_RATE)) : base;
        }
        return total;
    }

    private List<DayOffViolation> findDayOffViolations(List<Assignment> assignments, PeriodInput input) {
        Map<String, DayOffRequest> dayOffBySlot = input.dayOffs().stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId() + "|" + r.getDate(), Function.identity()));
        Map<Long, Employee> employeeById = input.employees().stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<SubstituteFinder.WorkSlot> proposed = assignments.stream()
                .map(a -> new SubstituteFinder.WorkSlot(employeeById.get(a.employeeId()), a.workDate(), a.startTime(), a.endTime()))
                .toList();
        List<DayOffViolation> violations = new ArrayList<>();
        for (Assignment a : assignments) {
            DayOffRequest dayOff = dayOffBySlot.get(a.employeeId() + "|" + a.workDate());
            if (dayOff == null) {
                continue;
            }
            List<Backup> backups = dayOff.getType() == DayOffType.MUST
                    ? findBackups(a, input, proposed)
                    : List.of();
            violations.add(new DayOffViolation(a.employeeId(), a.employeeName(), a.workDate(), dayOff.getType().name(),
                    a.shiftName(), a.startTime(), a.endTime(), backups,
                    dayOff.getFallbackList().stream().map(Enum::name).toList()));
        }
        return violations;
    }

    // 필수 휴무를 깨고 배정된 근무를 대신할 사람. 당사자가 못 나온다고 하면 바로 고를 수 있게 상위 몇 명만 준다
    private List<Backup> findBackups(Assignment assignment, PeriodInput input, List<SubstituteFinder.WorkSlot> proposed) {
        return substituteFinder.findForProposal(input.store().getId(), assignment.workDate(), assignment.startTime(),
                        assignment.endTime(), assignment.employeeId(), input.start(), input.end(), proposed).stream()
                .limit(MAX_BACKUPS)
                .map(c -> new Backup(c.employee().getId(), c.employee().getName(), c.eligible(), c.reasons()))
                .toList();
    }

    private Metrics computeMetrics(List<Assignment> assignments, PeriodInput input) {
        int preferenceTotal = 0;
        int preferenceHonored = 0;
        for (Assignment a : assignments) {
            if (!input.usesPreferences(a.employeeId())) {
                continue;
            }
            preferenceTotal++;
            PreferenceLevel level = input.levelOf(a.employeeId(), a.workDate(), a.startTime(), a.endTime());
            if (level == PreferenceLevel.PREFERRED || !input.hasPreferredOn(a.employeeId(), a.workDate())) {
                preferenceHonored++;
            }
        }
        boolean hasPreferences = input.employees().stream().anyMatch(e -> input.usesPreferences(e.getId()));
        Double preferenceRate = !hasPreferences ? null
                : preferenceTotal == 0 ? 100.0
                : Math.round(preferenceHonored * 1000.0 / preferenceTotal) / 10.0;

        Map<Long, Long> weekendByEmployee = new HashMap<>();
        input.employees().forEach(e -> weekendByEmployee.put(e.getId(), 0L));
        assignments.stream()
                .filter(a -> PreferenceService.isWeekend(a.workDate()))
                .forEach(a -> weekendByEmployee.merge(a.employeeId(), 1L, Long::sum));
        int weekendGap = (int) (weekendByEmployee.values().stream().mapToLong(Long::longValue).max().orElse(0)
                - weekendByEmployee.values().stream().mapToLong(Long::longValue).min().orElse(0));

        int coveredSlots = 0;
        int requiredSlots = 0;
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> countBySlot = new HashMap<>();
        assignments.forEach(a -> countBySlot.merge(a.workDate() + "|" + a.startTime() + "|" + a.endTime(), 1, Integer::sum));
        for (int day = 0; day < input.numDays(); day++) {
            LocalDate date = input.dateOf(day);
            for (Shift shift : input.shifts()) {
                StaffingPlanService.Staffing staffing = input.staffing().of(shift, date);
                int actual = countBySlot.getOrDefault(date + "|" + shift.getStartTime() + "|" + shift.getEndTime(), 0);
                if (staffing.min() > 0) {
                    requiredSlots++;
                    if (actual >= staffing.min()) {
                        coveredSlots++;
                    }
                }
                if (actual >= staffing.min() && actual < staffing.target() && warnings.size() < MAX_WARNINGS) {
                    warnings.add(dateLabel(date) + " " + shift.getName() + " " + shift.getStartTime() + "~" + shift.getEndTime()
                            + "는 최소 인원만 채웠고 목표 인원은 " + (staffing.target() - actual) + "명 모자라요.");
                }
            }
        }
        double coverage = requiredSlots == 0 ? 100.0 : Math.round(coveredSlots * 1000.0 / requiredSlots) / 10.0;
        return new Metrics(hasPreferences ? preferenceTotal : null, hasPreferences ? preferenceHonored : null,
                preferenceRate, weekendGap, countLaborLawViolations(assignments, input), coverage, warnings);
    }

    // 엔진이 하드 제약으로 막는 항목을 결과에서 다시 확인한다. 정상이면 0
    private int countLaborLawViolations(List<Assignment> assignments, PeriodInput input) {
        Map<Long, Employee> employeeById = input.employees().stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        Map<Long, List<Assignment>> byEmployee = assignments.stream().collect(Collectors.groupingBy(Assignment::employeeId));
        int violations = 0;
        for (Map.Entry<Long, List<Assignment>> entry : byEmployee.entrySet()) {
            Map<LocalDate, Integer> weeklyHours = new HashMap<>();
            Set<LocalDate> workDays = new HashSet<>();
            for (Assignment a : entry.getValue()) {
                weeklyHours.merge(a.workDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                        shiftHours(a.startTime(), a.endTime()), Integer::sum);
                workDays.add(a.workDate());
                if (employeeById.get(entry.getKey()).isMinor() && isRestrictedForMinor(a.startTime(), a.endTime())) {
                    violations++;
                }
            }
            violations += (int) weeklyHours.values().stream().filter(h -> h > MAX_WEEKLY_HOURS).count();
            for (LocalDate day : workDays) {
                boolean fiveInRow = true;
                for (int i = 1; i <= MAX_CONSECUTIVE_DAYS; i++) {
                    if (!workDays.contains(day.plusDays(i))) {
                        fiveInRow = false;
                        break;
                    }
                }
                if (fiveInRow) {
                    violations++;
                }
            }
        }
        return violations;
    }

    private static String dateLabel(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth() + "("
                + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ")";
    }

    private int countChanges(List<Assignment> assignments, Set<String> currentSlots) {
        Set<String> proposed = new HashSet<>();
        for (Assignment a : assignments) {
            proposed.add(slotKey(a.employeeId(), a.workDate(), a.startTime()));
        }
        int added = (int) proposed.stream().filter(k -> !currentSlots.contains(k)).count();
        int removed = (int) currentSlots.stream().filter(k -> !proposed.contains(k)).count();
        return added + removed;
    }

    private List<EmployeeSummary> summarize(List<Assignment> assignments, List<Employee> employees) {
        Map<Long, List<Assignment>> byEmployee = assignments.stream()
                .collect(Collectors.groupingBy(Assignment::employeeId, LinkedHashMap::new, Collectors.toList()));
        return employees.stream()
                .map(e -> {
                    List<Assignment> own = byEmployee.getOrDefault(e.getId(), List.of());
                    int hours = own.stream().mapToInt(a -> shiftHours(a.startTime(), a.endTime())).sum();
                    return new EmployeeSummary(e.getId(), e.getName(), own.size(), hours);
                })
                .toList();
    }

    // 요일 전용 교대나 예외일로 0명인 날은 배정될 수 없으므로 부족으로 보지 않는다
    private boolean isOpen(PeriodInput input, Shift shift, int day) {
        LocalDate date = input.dateOf(day);
        return isShiftOpenOn(shift, date.getDayOfWeek().getValue() - 1) && input.staffing().of(shift, date).target() > 0;
    }

    @SuppressWarnings("unchecked")
    private List<Shortage> toShortages(Object report, PeriodInput input) {
        if (report == null) {
            return List.of();
        }
        List<Shift> shifts = input.shifts();
        return ((List<Map<String, Object>>) report).stream()
                .filter(r -> isOpen(input, shifts.get(toInt(r.get("shift"))), toInt(r.get("day"))))
                .map(r -> new Shortage(
                        input.dateOf(toInt(r.get("day"))),
                        shifts.get(toInt(r.get("shift"))).getName(),
                        toInt(r.get("required")),
                        toInt(r.get("actual")),
                        null))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Shortage> toSkillShortages(Object report, PeriodInput input) {
        if (report == null) {
            return List.of();
        }
        List<Shift> shifts = input.shifts();
        Map<String, String> skillNames = new HashMap<>();
        for (ShiftSkillRequirement r : input.skillRequirements()) {
            skillNames.put(SKILL_KEY_PREFIX + r.getSkill().getId(), r.getSkill().getName());
        }
        return ((List<Map<String, Object>>) report).stream()
                .filter(r -> isOpen(input, shifts.get(toInt(r.get("shift"))), toInt(r.get("day"))))
                .map(r -> new Shortage(
                        input.dateOf(toInt(r.get("day"))),
                        shifts.get(toInt(r.get("shift"))).getName(),
                        toInt(r.get("required")),
                        toInt(r.get("actual")),
                        skillNames.getOrDefault((String) r.get("skill"), (String) r.get("skill"))))
                .toList();
    }

    // 자정을 넘기는 근무도 양수로 계산. 엔진이 정수 시간만 받아서 30분 단위는 반올림한다
    static int shiftHours(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes <= 0) {
            minutes += 24 * 60;
        }
        return (int) Math.max(1, Math.round(minutes / 60.0));
    }

    // 22:00~06:00에 일부라도 걸치면 야간 근무
    static boolean isNightShift(LocalTime start, LocalTime end) {
        boolean crossesMidnight = !end.isAfter(start);
        return crossesMidnight || end.isAfter(NIGHT_START) || start.isBefore(NIGHT_END);
    }

    // 근로기준법상 18세 미만은 22시~06시 근무 불가. 하루 7시간 한도는 휴게 없이 교대 길이로만 본다
    static boolean isRestrictedForMinor(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes <= 0) {
            minutes += 24 * 60;
        }
        return isNightShift(start, end) || minutes > MINOR_MAX_SHIFT_MINUTES;
    }

    // 교대 시간이 가용 시간 안에 완전히 들어가야 근무 가능
    static boolean fitsAvailability(LocalTime shiftStart, LocalTime shiftEnd,
                                    LocalTime availableFrom, LocalTime availableTo) {
        if (shiftStart.isBefore(availableFrom)) {
            return false;
        }
        boolean crossesMidnight = !shiftEnd.isAfter(shiftStart);
        return crossesMidnight
                ? !availableTo.isBefore(END_OF_DAY)
                : !shiftEnd.isAfter(availableTo);
    }

    private boolean isShiftOpenOn(Shift shift, int dayIndex) {
        return shift.getDayOfWeek() == null || shift.getDayOfWeek().getValue() - 1 == dayIndex;
    }

    private int indexOfShiftId(List<Shift> shifts, Long shiftId) {
        for (int i = 0; i < shifts.size(); i++) {
            if (shifts.get(i).getId().equals(shiftId)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfShift(List<Shift> shifts, LocalTime start, LocalTime end) {
        for (int i = 0; i < shifts.size(); i++) {
            if (shifts.get(i).getStartTime().equals(start) && shifts.get(i).getEndTime().equals(end)) {
                return i;
            }
        }
        return -1;
    }

    private int averageHourlyWage(List<Employee> employees) {
        return (int) employees.stream()
                .mapToInt(e -> e.getHourlyWage() != null ? e.getHourlyWage() : 0)
                .average()
                .orElse(0);
    }

    private int toDayIndex(Availability availability) {
        return availability.getDayOfWeek().getValue() - 1;
    }

    private static Map<String, Integer> slot(int day, int shift) {
        return Map.of("day", day, "shift", shift);
    }

    private String employeeKey(Employee employee) {
        return EMPLOYEE_KEY_PREFIX + employee.getId();
    }

    private static String slotKey(Long employeeId, LocalDate date, LocalTime start) {
        return employeeId + "|" + date + "|" + start;
    }

    private static int toInt(Object value) {
        return ((Number) value).intValue();
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static long solveMsOf(Map<String, Object> result) {
        Long ms = toLong(result.get("solve_ms"));
        return ms != null ? ms : 0;
    }
}
