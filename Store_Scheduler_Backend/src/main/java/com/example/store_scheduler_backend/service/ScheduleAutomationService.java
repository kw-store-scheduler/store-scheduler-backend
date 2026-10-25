package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ScheduleProposal;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Assignment;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Backup;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Candidate;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.DayOffViolation;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.EmployeeSummary;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Notice;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Shortage;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleProposalRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 엔진은 직원을 "emp_{id}", 날짜를 weekStart 기준 인덱스(0=월), 교대를 shifts 배열 인덱스로 다룬다
@Service
@RequiredArgsConstructor
public class ScheduleAutomationService {

    private static final int DAYS_IN_WEEK = 7;
    private static final String EMPLOYEE_KEY_PREFIX = "emp_";
    private static final String SKILL_KEY_PREFIX = "skill_";
    private static final int SOLVER_TIME_LIMIT_SECONDS = 10;
    private static final double NIGHT_BONUS_RATE = 0.5;
    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);
    // 가용 시간은 자정을 넘겨 등록할 수 없어서, 23:59까지 가능하면 자정 넘김 교대도 가능한 것으로 본다
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59);
    private static final int MAX_BACKUPS = 3;
    private static final int FULL_TIME_MUST_OFF_WEIGHT = 9_000_000;

    // 같은 입력을 가중치만 바꿔 다시 풀어 성격이 다른 후보안을 만든다. 키는 엔진의 weight_overrides 이름
    private record Plan(String label, String description, Map<String, Object> weightOverrides) {
    }

    private static final List<Plan> PLANS = List.of(
            new Plan("균형 해", "기본 우선순위로 인원·휴무·인건비·공평성을 함께 고려", Map.of()),
            new Plan("최소 비용 해", "근무일 균등·주 15시간·기존 근무 유지보다 인건비 절감을 우선",
                    Map.of("FAIRNESS_W", 0, "LOW_HOURS_W", 0, "CHANGE_W", 0)),
            new Plan("최고 공평 해", "인건비가 늘더라도 직원 간 근무일 차이를 최소화", Map.of("FAIRNESS_W", 1_000_000)));

    private record LabeledSolution(String label, String description, List<Map<String, Object>> solution) {
    }

    private final PythonEngineRunner pythonEngineRunner;
    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final AvailabilityRepository availabilityRepository;
    private final ShiftRepository shiftRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleProposalRepository proposalRepository;
    private final ShiftSkillRequirementRepository requirementRepository;
    private final DayOffRequestRepository dayOffRequestRepository;
    private final ObjectMapper objectMapper;
    private final SubstituteFinder substituteFinder;
    private final FcmService fcmService;

    // weekStart(월요일)부터 7일간의 근무표 후보안을 만든다. 확정 전까지 근무표는 바뀌지 않는다.
    @Transactional
    public ScheduleProposalResponse generateProposal(Long storeId, LocalDate weekStart, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("근무표 시작일(weekStart)은 월요일이어야 합니다.");
        }

        List<Employee> employees = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
        List<Shift> shifts = shiftRepository.findByStoreId(storeId).stream()
                .sorted(Comparator.comparing(Shift::getStartTime).thenComparing(Shift::getId))
                .toList();
        if (employees.isEmpty() || shifts.isEmpty()) {
            throw new IllegalArgumentException("승인된 직원 또는 등록된 근무 시간대(Shift)가 없습니다.");
        }

        List<Schedule> current = scheduleRepository.findByStoreIdAndWorkDateBetween(
                storeId, weekStart, weekStart.plusDays(DAYS_IN_WEEK - 1));

        List<ShiftSkillRequirement> skillRequirements = requirementRepository.findByShift_Store_Id(storeId);
        List<DayOffRequest> dayOffs = dayOffRequestRepository.findByEmployee_StoreIdAndDateBetweenOrderByDateAsc(
                storeId, weekStart, weekStart.plusDays(DAYS_IN_WEEK - 1));
        Map<Long, Map<Integer, Availability>> availabilityByEmployee = availabilityRepository.findByEmployee_StoreId(storeId).stream()
                .collect(Collectors.groupingBy(
                        a -> a.getEmployee().getId(),
                        Collectors.toMap(this::toDayIndex, Function.identity())));
        Map<String, Object> config = buildEngineConfig(employees, shifts, skillRequirements, availabilityByEmployee);
        addOffRequests(config, dayOffs, weekStart, shifts.size(), store.isMustDayOffGuaranteed());
        config.put("prev_assignments", toPrevAssignments(current, weekStart, employees, shifts));

        // 인원·스킬 부족과 상태는 균형 해 기준. 균형 해가 없으면 다른 기준으로도 찾을 수 없다
        Map<String, Object> result = pythonEngineRunner.run(withPlan(config, PLANS.get(0)));
        String status = (String) result.get("status");
        if (result.get("schedule") == null) {
            return ScheduleProposalResponse.failed(status, weekStart);
        }
        long solveMs = solveMsOf(result);
        List<LabeledSolution> solutions = new ArrayList<>();
        solutions.add(new LabeledSolution(PLANS.get(0).label(), PLANS.get(0).description(), toSolution(result.get("schedule"))));
        for (Plan plan : PLANS.subList(1, PLANS.size())) {
            Map<String, Object> planResult = pythonEngineRunner.run(withPlan(config, plan));
            solveMs += solveMsOf(planResult);
            if (planResult.get("schedule") != null) {
                solutions.add(new LabeledSolution(plan.label(), plan.description(), toSolution(planResult.get("schedule"))));
            }
        }
        AssignmentContext context = new AssignmentContext(storeId, weekStart, employees, current, dayOffs, skillRequirements,
                availabilityByEmployee);
        List<Candidate> candidates = toCandidates(solutions, weekStart, shifts, context);
        List<Shortage> shortages = new ArrayList<>(toShortages(result.get("shortage_report"), weekStart, shifts));
        shortages.addAll(toSkillShortages(result.get("skill_shortage_report"), weekStart, shifts, skillRequirements));

        ScheduleProposal proposal = proposalRepository.save(new ScheduleProposal(store, weekStart, status, solveMs,
                objectMapper.writeValueAsString(candidates), objectMapper.writeValueAsString(shortages)));

        Notice notice = ScheduleProposalResponse.noticeFor(candidates, shortages);
        if (notice.needsAttention()) {
            fcmService.sendToDevice(store.getOwner().getDeviceToken(), "근무표 후보안 확인 필요",
                    weekStart + " 주 근무표: " + notice.summary());
        }
        return new ScheduleProposalResponse(proposal.getId(), true, status,
                ScheduleProposalResponse.messageFor(candidates.size(), shortages.size()),
                weekStart, solveMs, proposal.getStatus(), null, shortages, candidates, notice);
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

    private Map<String, Object> buildEngineConfig(List<Employee> employees, List<Shift> shifts,
                                                  List<ShiftSkillRequirement> skillRequirements,
                                                  Map<Long, Map<Integer, Availability>> availabilityByEmployee) {
        List<Integer> allShiftIndices = new ArrayList<>();
        for (int i = 0; i < shifts.size(); i++) {
            allShiftIndices.add(i);
        }

        List<Map<String, Object>> employeeConfigs = new ArrayList<>();
        for (Employee employee : employees) {
            Map<String, Object> config = new HashMap<>();
            config.put("id", employeeKey(employee));
            config.put("name", employee.getName());
            Map<Integer, Availability> availabilityByDay = availabilityByEmployee.getOrDefault(employee.getId(), Map.of());
            config.put("available_days", new ArrayList<>(availabilityByDay.keySet()));
            config.put("unavailable_slots", unavailableSlots(availabilityByDay, shifts));
            config.put("preferred_shifts", allShiftIndices);
            config.put("skills", employee.getSkills().stream().map(sk -> SKILL_KEY_PREFIX + sk.getId()).toList());
            config.put("hourly", employee.getHourlyWage() != null ? employee.getHourlyWage() : 0);
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

        Map<String, Object> config = new HashMap<>();
        config.put("employees", employeeConfigs);
        config.put("shifts", shiftConfigs);
        config.put("target_staff", targetStaff);
        config.put("max_staff", targetStaff);
        config.put("min_staff", minStaff);
        config.put("base_hourly", averageHourlyWage(employees));
        config.put("night_bonus", NIGHT_BONUS_RATE);
        config.put("num_days", DAYS_IN_WEEK);
        config.put("time_limit", SOLVER_TIME_LIMIT_SECONDS);
        config.put("skill_requirements", toEngineSkillRequirements(skillRequirements, shifts));
        return config;
    }

    // 정규직 필수 휴무는 알바(엔진 기본 1천만)보다 낮게 넘겨, 인원을 채우려고 휴무를 깨야 하면 정규직 휴무를 먼저 깬다
    private Map<String, Object> toEngineOffRequest(DayOffRequest request, LocalDate weekStart) {
        Map<String, Object> offRequest = new HashMap<>();
        offRequest.put("day", (int) ChronoUnit.DAYS.between(weekStart, request.getDate()));
        offRequest.put("type", request.getType() == DayOffType.MUST ? "must" : "prefer");
        if (request.getType() == DayOffType.MUST && request.getEmployee().getEmploymentType() == EmploymentType.FULL_TIME) {
            offRequest.put("weight", FULL_TIME_MUST_OFF_WEIGHT);
        }
        return offRequest;
    }

    // 필수 휴무 보장이 켜져 있으면 그날의 모든 교대를 근무 불가(하드 제약)로도 넘긴다
    @SuppressWarnings("unchecked")
    private void addOffRequests(Map<String, Object> config, List<DayOffRequest> dayOffs, LocalDate weekStart,
                                int shiftCount, boolean guaranteeMustDayOff) {
        Map<Long, List<Map<String, Object>>> byEmployee = dayOffs.stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId(), Collectors.mapping(
                        r -> toEngineOffRequest(r, weekStart),
                        Collectors.toList())));
        for (Map<String, Object> employee : (List<Map<String, Object>>) config.get("employees")) {
            Long id = Long.valueOf(((String) employee.get("id")).substring(EMPLOYEE_KEY_PREFIX.length()));
            List<Map<String, Object>> offRequests = byEmployee.getOrDefault(id, List.of());
            employee.put("off_requests", offRequests);
            if (guaranteeMustDayOff) {
                List<Map<String, Integer>> slots = new ArrayList<>((List<Map<String, Integer>>) employee.get("unavailable_slots"));
                offRequests.stream()
                        .filter(r -> "must".equals(r.get("type")))
                        .forEach(r -> {
                            for (int s = 0; s < shiftCount; s++) {
                                slots.add(Map.of("day", (Integer) r.get("day"), "shift", s));
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

    // 가용 시간을 등록한 요일 중 근무할 수 없는 (요일, 교대)
    private List<Map<String, Integer>> unavailableSlots(Map<Integer, Availability> availabilityByDay, List<Shift> shifts) {
        List<Map<String, Integer>> slots = new ArrayList<>();
        availabilityByDay.forEach((day, availability) -> {
            for (int s = 0; s < shifts.size(); s++) {
                Shift shift = shifts.get(s);
                if (!isShiftOpenOn(shift, day) || !fitsAvailability(shift.getStartTime(), shift.getEndTime(),
                        availability.getStartTime(), availability.getEndTime())) {
                    slots.add(Map.of("day", day, "shift", s));
                }
            }
        });
        return slots;
    }

    // 삭제된 교대나 승인 취소된 직원의 근무처럼 현재 설정과 맞지 않는 건은 제외
    private List<Map<String, Object>> toPrevAssignments(List<Schedule> current, LocalDate weekStart,
                                                        List<Employee> employees, List<Shift> shifts) {
        Set<Long> employeeIds = employees.stream().map(Employee::getId).collect(Collectors.toSet());
        List<Map<String, Object>> prev = new ArrayList<>();
        for (Schedule schedule : current) {
            int shiftIndex = indexOfShift(shifts, schedule.getStartTime(), schedule.getEndTime());
            if (shiftIndex < 0 || !employeeIds.contains(schedule.getEmployee().getId())) {
                continue;
            }
            prev.add(Map.of(
                    "employee_id", employeeKey(schedule.getEmployee()),
                    "day", (int) ChronoUnit.DAYS.between(weekStart, schedule.getWorkDate()),
                    "shift", shiftIndex));
        }
        return prev;
    }

    // 배정 사유와 후보안 지표 계산에 쓰는 생성 시점의 매장 데이터
    private record AssignmentContext(Long storeId, LocalDate weekStart, List<Employee> employees, List<Schedule> current,
                                     List<DayOffRequest> dayOffs,
                                     List<ShiftSkillRequirement> skillRequirements,
                                     Map<Long, Map<Integer, Availability>> availabilityByEmployee) {
    }

    // 기준별 해를 순서대로 후보안으로 만든다. 배치가 같은 해는 하나로 합치고 라벨만 이어 붙인다
    private List<Candidate> toCandidates(List<LabeledSolution> solutions, LocalDate weekStart, List<Shift> shifts,
                                         AssignmentContext context) {
        List<Employee> employees = context.employees();

        Map<String, Employee> employeeByKey = employees.stream()
                .collect(Collectors.toMap(this::employeeKey, Function.identity()));
        Set<String> currentSlots = context.current().stream()
                .map(s -> slotKey(s.getEmployee().getId(), s.getWorkDate(), s.getStartTime()))
                .collect(Collectors.toSet());

        List<Candidate> candidates = new ArrayList<>();
        List<Set<String>> slotsByCandidate = new ArrayList<>();
        for (LabeledSolution solution : solutions) {
            List<Assignment> assignments = withReasons(toAssignments(solution.solution(), weekStart, employeeByKey, shifts),
                    currentSlots, context);
            Set<String> slots = assignments.stream()
                    .map(a -> slotKey(a.employeeId(), a.workDate(), a.startTime()))
                    .collect(Collectors.toSet());
            int same = slotsByCandidate.indexOf(slots);
            if (same >= 0) {
                Candidate c = candidates.get(same);
                candidates.set(same, new Candidate(c.index(), c.label() + " · " + solution.label(), c.description(),
                        c.estimatedCost(), c.changedFromCurrent(), c.employees(), c.assignments(), c.dayOffViolations()));
                continue;
            }
            slotsByCandidate.add(slots);
            candidates.add(new Candidate(candidates.size(), solution.label(), solution.description(),
                    estimateCost(assignments, employees), countChanges(assignments, currentSlots),
                    summarize(assignments, employees), assignments, findDayOffViolations(assignments, context)));
        }
        return candidates;
    }

    @SuppressWarnings("unchecked")
    private List<Assignment> toAssignments(List<Map<String, Object>> solution, LocalDate weekStart,
                                           Map<String, Employee> employeeByKey, List<Shift> shifts) {
        List<Assignment> assignments = new ArrayList<>();
        for (Map<String, Object> entry : solution) {
            String key = (String) entry.get("employee_id");
            Employee employee = employeeByKey.get(key);
            if (employee == null) {
                throw new IllegalStateException("엔진 결과에 매장 소속이 아닌 직원이 포함되어 있습니다: " + key);
            }
            for (Map<String, Object> slot : (List<Map<String, Object>>) entry.get("assignments")) {
                Shift shift = shifts.get(toInt(slot.get("shift")));
                assignments.add(new Assignment(employee.getId(), employee.getName(),
                        weekStart.plusDays(toInt(slot.get("day"))), shift.getName(),
                        shift.getStartTime(), shift.getEndTime(), List.of()));
            }
        }
        assignments.sort(Comparator.comparing(Assignment::workDate).thenComparing(Assignment::startTime));
        return assignments;
    }

    private List<Assignment> withReasons(List<Assignment> assignments, Set<String> currentSlots, AssignmentContext context) {
        Map<Long, Employee> employeeById = context.employees().stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        Map<String, DayOffType> dayOffBySlot = context.dayOffs().stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId() + "|" + r.getDate(), DayOffRequest::getType));
        Map<Long, Integer> hoursByEmployee = new HashMap<>();
        for (Assignment a : assignments) {
            hoursByEmployee.merge(a.employeeId(), shiftHours(a.startTime(), a.endTime()), Integer::sum);
        }
        long averageHours = Math.round(hoursByEmployee.values().stream().mapToInt(Integer::intValue).sum()
                / (double) context.employees().size());

        List<Assignment> result = new ArrayList<>();
        for (Assignment a : assignments) {
            Employee employee = employeeById.get(a.employeeId());
            List<String> reasons = new ArrayList<>();
            if (currentSlots.contains(slotKey(a.employeeId(), a.workDate(), a.startTime()))) {
                reasons.add("기존 근무표 유지");
            }
            List<String> coveredSkills = requiredSkillsHeldBy(employee, a, context.skillRequirements());
            if (!coveredSkills.isEmpty()) {
                reasons.add("필요 스킬 보유: " + String.join(", ", coveredSkills));
            }
            DayOffType dayOff = dayOffBySlot.get(a.employeeId() + "|" + a.workDate());
            if (dayOff != null) {
                reasons.add((dayOff == DayOffType.MUST ? "필수" : "희망") + " 휴무 신청일이지만 배정됨");
            }
            Availability availability = context.availabilityByEmployee()
                    .getOrDefault(a.employeeId(), Map.of())
                    .get(a.workDate().getDayOfWeek().getValue() - 1);
            if (availability != null) {
                reasons.add("가용 시간 " + availability.getStartTime() + "~" + availability.getEndTime());
            }
            reasons.add("이번 주 " + hoursByEmployee.get(a.employeeId()) + "시간 (직원 평균 " + averageHours + "시간)");
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

    private List<DayOffViolation> findDayOffViolations(List<Assignment> assignments, AssignmentContext context) {
        Map<String, DayOffRequest> dayOffBySlot = context.dayOffs().stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId() + "|" + r.getDate(), Function.identity()));
        Map<Long, Employee> employeeById = context.employees().stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<SubstituteFinder.WorkSlot> proposedWeek = assignments.stream()
                .map(a -> new SubstituteFinder.WorkSlot(employeeById.get(a.employeeId()), a.workDate(), a.startTime(), a.endTime()))
                .toList();
        List<DayOffViolation> violations = new ArrayList<>();
        for (Assignment a : assignments) {
            DayOffRequest dayOff = dayOffBySlot.get(a.employeeId() + "|" + a.workDate());
            if (dayOff == null) {
                continue;
            }
            List<Backup> backups = dayOff.getType() == DayOffType.MUST
                    ? findBackups(a, context, proposedWeek)
                    : List.of();
            violations.add(new DayOffViolation(a.employeeId(), a.employeeName(), a.workDate(), dayOff.getType().name(),
                    a.shiftName(), a.startTime(), a.endTime(), backups));
        }
        return violations;
    }

    // 필수 휴무를 깨고 배정된 근무를 대신할 사람. 당사자가 못 나온다고 하면 바로 고를 수 있게 상위 몇 명만 준다
    private List<Backup> findBackups(Assignment assignment, AssignmentContext context,
                                     List<SubstituteFinder.WorkSlot> proposedWeek) {
        return substituteFinder.findForProposal(context.storeId(), assignment.workDate(), assignment.startTime(),
                        assignment.endTime(), assignment.employeeId(), context.weekStart(), proposedWeek).stream()
                .limit(MAX_BACKUPS)
                .map(c -> new Backup(c.employee().getId(), c.employee().getName(), c.eligible(), c.reasons()))
                .toList();
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

    @SuppressWarnings("unchecked")
    private List<Shortage> toShortages(Object report, LocalDate weekStart, List<Shift> shifts) {
        if (report == null) {
            return List.of();
        }
        return ((List<Map<String, Object>>) report).stream()
                // 요일 전용 교대는 다른 요일에 배정될 수 없으므로 부족으로 보지 않는다
                .filter(r -> isShiftOpenOn(shifts.get(toInt(r.get("shift"))), toInt(r.get("day"))))
                .map(r -> new Shortage(
                        weekStart.plusDays(toInt(r.get("day"))),
                        shifts.get(toInt(r.get("shift"))).getName(),
                        toInt(r.get("required")),
                        toInt(r.get("actual")),
                        null))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Shortage> toSkillShortages(Object report, LocalDate weekStart, List<Shift> shifts,
                                            List<ShiftSkillRequirement> skillRequirements) {
        if (report == null) {
            return List.of();
        }
        Map<String, String> skillNames = new HashMap<>();
        for (ShiftSkillRequirement r : skillRequirements) {
            skillNames.put(SKILL_KEY_PREFIX + r.getSkill().getId(), r.getSkill().getName());
        }
        return ((List<Map<String, Object>>) report).stream()
                .filter(r -> isShiftOpenOn(shifts.get(toInt(r.get("shift"))), toInt(r.get("day"))))
                .map(r -> new Shortage(
                        weekStart.plusDays(toInt(r.get("day"))),
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
