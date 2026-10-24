package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ScheduleProposal;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Assignment;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Candidate;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.EmployeeSummary;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Shortage;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
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

    private final PythonEngineRunner pythonEngineRunner;
    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final AvailabilityRepository availabilityRepository;
    private final ShiftRepository shiftRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleProposalRepository proposalRepository;
    private final ShiftSkillRequirementRepository requirementRepository;
    private final ObjectMapper objectMapper;

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
        Map<String, Object> config = buildEngineConfig(storeId, employees, shifts, skillRequirements);
        config.put("prev_assignments", toPrevAssignments(current, weekStart, employees, shifts));
        Map<String, Object> result = pythonEngineRunner.run(config);
        String status = (String) result.get("status");

        if (result.get("schedule") == null) {
            return ScheduleProposalResponse.failed(status, weekStart);
        }

        List<Candidate> candidates = toCandidates(result, weekStart, employees, shifts, current);
        List<Shortage> shortages = new ArrayList<>(toShortages(result.get("shortage_report"), weekStart, shifts));
        shortages.addAll(toSkillShortages(result.get("skill_shortage_report"), weekStart, shifts, skillRequirements));
        Long solveMs = toLong(result.get("solve_ms"));

        ScheduleProposal proposal = proposalRepository.save(new ScheduleProposal(store, weekStart, status, solveMs,
                objectMapper.writeValueAsString(candidates), objectMapper.writeValueAsString(shortages)));

        return new ScheduleProposalResponse(proposal.getId(), true, status,
                ScheduleProposalResponse.messageFor(candidates.size(), shortages.size()),
                weekStart, solveMs, proposal.getStatus(), null, shortages, candidates);
    }

    private Map<String, Object> buildEngineConfig(Long storeId, List<Employee> employees, List<Shift> shifts,
                                                  List<ShiftSkillRequirement> skillRequirements) {
        Map<Long, Map<Integer, Availability>> availabilityByEmployee = availabilityRepository.findByEmployee_StoreId(storeId).stream()
                .collect(Collectors.groupingBy(
                        a -> a.getEmployee().getId(),
                        Collectors.toMap(this::toDayIndex, Function.identity())));

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
        config.put("min_staff", minStaff);
        config.put("base_hourly", averageHourlyWage(employees));
        config.put("night_bonus", NIGHT_BONUS_RATE);
        config.put("num_days", DAYS_IN_WEEK);
        config.put("time_limit", SOLVER_TIME_LIMIT_SECONDS);
        config.put("skill_requirements", toEngineSkillRequirements(skillRequirements, shifts));
        return config;
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

    // 엔진의 대표 해(schedule)를 0번, 동일 비용의 대안 해(alt_schedules)를 1번부터 후보안으로 만든다
    @SuppressWarnings("unchecked")
    private List<Candidate> toCandidates(Map<String, Object> result, LocalDate weekStart, List<Employee> employees,
                                         List<Shift> shifts, List<Schedule> current) {
        List<List<Map<String, Object>>> solutions = new ArrayList<>();
        solutions.add((List<Map<String, Object>>) result.get("schedule"));
        Object alternatives = result.get("alt_schedules");
        if (alternatives != null) {
            solutions.addAll((List<List<Map<String, Object>>>) alternatives);
        }

        Map<String, Employee> employeeByKey = employees.stream()
                .collect(Collectors.toMap(this::employeeKey, Function.identity()));
        Set<String> currentSlots = current.stream()
                .map(s -> slotKey(s.getEmployee().getId(), s.getWorkDate(), s.getStartTime()))
                .collect(Collectors.toSet());

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < solutions.size(); i++) {
            List<Assignment> assignments = toAssignments(solutions.get(i), weekStart, employeeByKey, shifts);
            candidates.add(new Candidate(i, estimateCost(assignments, employees),
                    countChanges(assignments, currentSlots), summarize(assignments, employees), assignments));
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
                        shift.getStartTime(), shift.getEndTime()));
            }
        }
        assignments.sort(Comparator.comparing(Assignment::workDate).thenComparing(Assignment::startTime));
        return assignments;
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
}
