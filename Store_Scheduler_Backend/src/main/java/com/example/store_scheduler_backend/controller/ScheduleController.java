package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.dto.ScheduleResponseDto;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.service.EmployeeService;
import com.example.store_scheduler_backend.service.AvailabilityService;
import com.example.store_scheduler_backend.service.ShiftService;
import com.example.store_scheduler_backend.service.ScheduleAutomationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
@Tag(name = "스케줄 자동화 API", description = "파이썬 알고리즘 연동 및 스케줄 관리")
public class ScheduleController {

    private final EmployeeService employeeService;
    private final AvailabilityService availabilityService;
    private final ShiftService shiftService;
    private final ScheduleAutomationService automationService;

    private final ScheduleRepository scheduleRepository;

    /**
     * DB 연동 자동 스케줄링 엔진 가동 API
     */
    @Operation(summary = "스케줄 자동 생성", description = "DB의 직원 정보를 바탕으로 최적의 스케줄을 생성합니다.")
    @PostMapping("/automate")
    public ResponseEntity<Map<String, Object>> automateSchedule() {

        List<Employee> employees = employeeService.findEmployees();
        List<Availability> allAvailabilities = availabilityService.findAvailabilities();
        List<Shift> shifts = shiftService.findShifts();

        // 방어 로직
        if (shifts.isEmpty() || employees.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "DB에 등록된 직원이나 근무 시간대(Shift) 데이터가 없습니다."));
        }

        List<Map<String, Object>> empConfigList = new ArrayList<>();
        for (Employee emp : employees) {
            Map<String, Object> empMap = new HashMap<>();
            empMap.put("id", "emp_" + emp.getId());
            empMap.put("name", emp.getName());
            empMap.put("hourly_wage", emp.getHourlyWage());

            List<Integer> availableDays = allAvailabilities.stream()
                    .filter(a -> a.getEmployee() != null && a.getEmployee().getId().equals(emp.getId()))
                    .map(a -> a.getDayOfWeek().getValue() - 1)
                    .distinct()
                    .collect(Collectors.toList());

            empMap.put("available_days", availableDays);
            // 0부터 숫자 생성
            List<Integer> dynamicPreferredShifts = java.util.stream.IntStream.range(0, shifts.size())
                    .boxed()
                    .collect(java.util.stream.Collectors.toList());
            empMap.put("preferred_shifts", dynamicPreferredShifts);
            empConfigList.add(empMap);
        }

        List<Map<String, Object>> shiftConfigList = new ArrayList<>();
        List<Integer> targetStaffList = new ArrayList<>();
        List<Integer> minStaffList = new ArrayList<>();

        for (Shift shift : shifts) {
            long hours = java.time.Duration.between(shift.getStartTime(), shift.getEndTime()).toHours();
            if (hours <= 0) hours = 4; // 기본값 (시간 계산 오류 방지용)

            boolean isNight = shift.getEndTime().isAfter(java.time.LocalTime.of(22, 0)) ||
                    shift.getStartTime().isBefore(java.time.LocalTime.of(6, 0));

            shiftConfigList.add(Map.of(
                    "name", shift.getName(),
                    "hours", (int) hours,
                    "is_night", isNight
            ));

            targetStaffList.add(shift.getRequiredStaff());
            minStaffList.add(1); // 최소 인원 1명 기본 배정
        }

        Map<String, Object> finalConfig = new HashMap<>();
        finalConfig.put("employees", empConfigList);
        finalConfig.put("shifts", shiftConfigList);
        finalConfig.put("target_staff", targetStaffList);
        finalConfig.put("min_staff", minStaffList);
        int baseHourly = employees.get(0).getHourlyWage();
        finalConfig.put("base_hourly", baseHourly);
        finalConfig.put("night_bonus", 0.5);
        finalConfig.put("time_limit", 10);

        Map<String, Object> optimizationResult = automationService.runOptimization(finalConfig);

        return ResponseEntity.ok(optimizationResult);
    }

    @GetMapping
    public ResponseEntity<List<ScheduleResponseDto>> getSchedulesByStore(@RequestParam Long storeId) {

        List<ScheduleResponseDto> schedules = scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(storeId)
                .stream()
                .map(ScheduleResponseDto::new)
                .collect(Collectors.toList());

        return ResponseEntity.ok(schedules);
    }
}