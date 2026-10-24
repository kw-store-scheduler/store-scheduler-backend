package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.dto.ScheduleGenerationResponse;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 매장 데이터로 엔진 입력(config)을 만들고, 엔진 결과를 근무표(Schedule)로 저장한다.
 *
 * 엔진은 직원을 "emp_{id}", 날짜를 주 시작일 기준 인덱스(0=월 ~ 6=일), 근무 시간대를 shifts 배열 인덱스로 다룬다.
 * 이 클래스가 그 인덱스와 실제 Employee / 날짜 / Shift 사이의 변환을 맡는다.
 *
 * 엔진에 넘기는 근무 가능 여부
 * - available_days: 가용 시간을 등록한 요일
 * - unavailable_slots: 그 요일 중 가용 시간 안에 들어가지 않는 교대, 해당 요일 전용이 아닌 교대
 * - prev_assignments: 같은 주를 다시 생성할 때 기존 근무표 (변경 최소화)
 */
@Service
@RequiredArgsConstructor
public class ScheduleAutomationService {

    private static final int DAYS_IN_WEEK = 7;
    private static final String EMPLOYEE_KEY_PREFIX = "emp_";
    private static final int SOLVER_TIME_LIMIT_SECONDS = 10;
    private static final double NIGHT_BONUS_RATE = 0.5;
    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);
    // 가용 시간은 자정을 넘겨 등록할 수 없으므로, 23:59까지 가능하면 마감(자정 넘김) 근무도 가능한 것으로 본다
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59);

    private final PythonEngineRunner pythonEngineRunner;
    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final AvailabilityRepository availabilityRepository;
    private final ShiftRepository shiftRepository;
    private final ScheduleRepository scheduleRepository;
    private final FcmService fcmService;

    /**
     * weekStart(월요일)부터 7일간의 근무표를 생성한다.
     * 같은 주에 이미 근무표가 있으면 새 결과로 교체한다.
     */
    @Transactional
    public ScheduleGenerationResponse generateWeeklySchedule(Long storeId, LocalDate weekStart, String managerUsername) {
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

        LocalDate weekEnd = weekStart.plusDays(DAYS_IN_WEEK - 1);
        List<Schedule> previous = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, weekStart, weekEnd);

        Map<String, Object> config = buildEngineConfig(storeId, employees, shifts);
        config.put("prev_assignments", toPrevAssignments(previous, weekStart, employees, shifts));
        Map<String, Object> result = pythonEngineRunner.run(config);
        String status = (String) result.get("status");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assigned = (List<Map<String, Object>>) result.get("schedule");
        if (assigned == null) {
            return ScheduleGenerationResponse.failed(status, weekStart);
        }

        scheduleRepository.deleteAll(previous);
        int savedCount = saveSchedules(store, weekStart, employees, shifts, assigned);
        notifyEmployees(employees);

        return ScheduleGenerationResponse.success(
                status,
                weekStart,
                savedCount,
                toLong(result.get("cost")),
                toLong(result.get("solve_ms")),
                toShortages(result.get("shortage_report"), weekStart, shifts));
    }

    private Map<String, Object> buildEngineConfig(Long storeId, List<Employee> employees, List<Shift> shifts) {
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
        return config;
    }

    /**
     * 가용 시간을 등록한 요일 중, 근무가 불가능한 (요일, 교대) 목록
     */
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

    /**
     * 기존 근무표를 엔진의 (직원, 요일, 교대) 형식으로 변환한다.
     * 이후 삭제된 교대나 승인 취소된 직원의 근무처럼 현재 설정과 맞지 않는 건은 제외한다.
     */
    private List<Map<String, Object>> toPrevAssignments(List<Schedule> previous, LocalDate weekStart,
                                                        List<Employee> employees, List<Shift> shifts) {
        Set<Long> employeeIds = employees.stream().map(Employee::getId).collect(Collectors.toSet());
        List<Map<String, Object>> prev = new ArrayList<>();
        for (Schedule schedule : previous) {
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

    private int indexOfShift(List<Shift> shifts, LocalTime start, LocalTime end) {
        for (int i = 0; i < shifts.size(); i++) {
            if (shifts.get(i).getStartTime().equals(start) && shifts.get(i).getEndTime().equals(end)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private int saveSchedules(Store store, LocalDate weekStart, List<Employee> employees,
                              List<Shift> shifts, List<Map<String, Object>> assigned) {
        Map<String, Employee> employeeByKey = employees.stream()
                .collect(Collectors.toMap(this::employeeKey, Function.identity()));

        List<Schedule> schedules = new ArrayList<>();
        for (Map<String, Object> entry : assigned) {
            String key = (String) entry.get("employee_id");
            Employee employee = employeeByKey.get(key);
            if (employee == null) {
                throw new IllegalStateException("엔진 결과에 매장 소속이 아닌 직원이 포함되어 있습니다: " + key);
            }
            for (Map<String, Object> assignment : (List<Map<String, Object>>) entry.get("assignments")) {
                Shift shift = shifts.get(toInt(assignment.get("shift")));
                Schedule schedule = new Schedule();
                schedule.setEmployee(employee);
                schedule.setStore(store);
                schedule.setWorkDate(weekStart.plusDays(toInt(assignment.get("day"))));
                schedule.setStartTime(shift.getStartTime());
                schedule.setEndTime(shift.getEndTime());
                schedules.add(schedule);
            }
        }
        scheduleRepository.saveAll(schedules);
        return schedules.size();
    }

    @SuppressWarnings("unchecked")
    private List<ScheduleGenerationResponse.Shortage> toShortages(Object report, LocalDate weekStart, List<Shift> shifts) {
        if (report == null) {
            return List.of();
        }
        return ((List<Map<String, Object>>) report).stream()
                // 특정 요일 전용 교대는 다른 요일에 아무도 배정될 수 없으므로 부족으로 보지 않는다
                .filter(r -> isShiftOpenOn(shifts.get(toInt(r.get("shift"))), toInt(r.get("day"))))
                .map(r -> new ScheduleGenerationResponse.Shortage(
                        weekStart.plusDays(toInt(r.get("day"))),
                        shifts.get(toInt(r.get("shift"))).getName(),
                        toInt(r.get("required")),
                        toInt(r.get("actual"))))
                .toList();
    }

    private void notifyEmployees(List<Employee> employees) {
        for (Employee employee : employees) {
            fcmService.sendToDevice(
                    employee.getUser().getDeviceToken(),
                    "근무표 업데이트",
                    "새로운 근무표가 등록되었습니다. 확인해주세요."
            );
        }
    }

    /**
     * 근무 시간(시간 단위, 반올림). 자정을 넘기는 근무(예: 22:00~02:00)도 처리한다.
     * 엔진이 정수 시간만 받기 때문에 30분 단위 근무는 반올림된다.
     */
    static int shiftHours(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes <= 0) {
            minutes += 24 * 60;
        }
        return (int) Math.max(1, Math.round(minutes / 60.0));
    }

    /**
     * 근무 시간 중 일부라도 야간(22:00~06:00)에 걸치면 야간 근무로 본다.
     */
    static boolean isNightShift(LocalTime start, LocalTime end) {
        boolean crossesMidnight = !end.isAfter(start);
        return crossesMidnight || end.isAfter(NIGHT_START) || start.isBefore(NIGHT_END);
    }

    /**
     * 교대 시간이 가용 시간 안에 완전히 들어가는지 확인한다.
     * 자정을 넘기는 교대는 가용 시간이 그날 끝(23:59)까지일 때만 가능한 것으로 본다.
     */
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

    private static int toInt(Object value) {
        return ((Number) value).intValue();
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
