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

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final EmployeeService employeeService;
    private final AvailabilityService availabilityService;
    private final ShiftService shiftService;
    private final ScheduleAutomationService automationService;

    private final ScheduleRepository scheduleRepository;

    /**
     * 100% 리얼 DB 연동 자동 스케줄링 엔진 가동 API
     */
    @PostMapping("/automate")
    public ResponseEntity<Map<String, Object>> automateSchedule() {

        // 1. DB에서 리얼 데이터들 전부 로드
        List<Employee> employees = employeeService.findEmployees();
        List<Availability> allAvailabilities = availabilityService.findAvailabilities();
        List<Shift> shifts = shiftService.findShifts();

        // 방어 로직: 필수 데이터가 비어있으면 백포스 동작 중단
        if (shifts.isEmpty() || employees.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "DB에 등록된 직원이나 근무 시간대(Shift) 데이터가 없습니다."));
        }

        // 2. 파이썬용 employees 구조 빌드
        List<Map<String, Object>> empConfigList = new ArrayList<>();
        for (Employee emp : employees) {
            Map<String, Object> empMap = new HashMap<>();
            empMap.put("id", "emp_" + emp.getId());
            empMap.put("name", emp.getName());

            // DB에 등록된 알바생별 진짜 가용 요일 추출 (자바 1=월~7=일 -> 파이썬 0=월~6=일 변환)
            List<Integer> availableDays = allAvailabilities.stream()
                    .filter(a -> a.getEmployee() != null && a.getEmployee().getId().equals(emp.getId()))
                    .map(a -> a.getDayOfWeek().getValue() - 1)
                    .distinct()
                    .collect(Collectors.toList());

            empMap.put("available_days", availableDays);
            empMap.put("preferred_shifts", List.of(0, 1)); // 선호 시간대 매핑
            empConfigList.add(empMap);
        }

        // 3. DB에 저장된 진짜 Shift(시간대) 정보 추출하여 가공
        List<Map<String, Object>> shiftConfigList = new ArrayList<>();
        List<Integer> targetStaffList = new ArrayList<>();
        List<Integer> minStaffList = new ArrayList<>();

        for (Shift shift : shifts) {
            // 시간대별 총 근무 시간 계산 (종료 시간 - 시작 시간)
            long hours = java.time.Duration.between(shift.getStartTime(), shift.getEndTime()).toHours();
            if (hours <= 0) hours = 4; // 시간 계산 오류 방지용 기본값

            // 야간 근무 여부 판별 로직 (22시 이후 근무 종료거나 06시 이전 시작 시 야간으로 세팅)
            boolean isNight = shift.getEndTime().isAfter(java.time.LocalTime.of(22, 0)) ||
                    shift.getStartTime().isBefore(java.time.LocalTime.of(6, 0));

            shiftConfigList.add(Map.of(
                    "name", shift.getName(),
                    "hours", (int) hours,
                    "is_night", isNight
            ));

            // DB에 저장된 실제 필요 인원 설정값을 파이썬 배열에 동적으로 주입
            targetStaffList.add(shift.getRequiredStaff());
            minStaffList.add(1); // 최소 인원은 최적화 모델 구동을 위해 1명 기본 배정
        }

        // 4. 파이썬 파라미터 조립
        Map<String, Object> finalConfig = new HashMap<>();
        finalConfig.put("employees", empConfigList);
        finalConfig.put("shifts", shiftConfigList);
        finalConfig.put("target_staff", targetStaffList); // 리얼 DB 값 바인딩
        finalConfig.put("min_staff", minStaffList);       // 리얼 DB 값 바인딩
        finalConfig.put("base_hourly", 9860);
        finalConfig.put("night_bonus", 0.5);
        finalConfig.put("time_limit", 10);

        // 5. 파이썬 연동 가동 및 결과 반환
        Map<String, Object> optimizationResult = automationService.runOptimization(finalConfig);

        return ResponseEntity.ok(optimizationResult);
    }

    @GetMapping
    public ResponseEntity<List<ScheduleResponseDto>> getSchedulesByStore(@RequestParam Long storeId) {

        // 1. DB에서 해당 매장의 스케줄을 싹 다 가져와서
        // 2. 아까 만든 포장 상자(DTO)로 깔끔하게 변환(map)한 뒤 리스트로 반환
        List<ScheduleResponseDto> schedules = scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(storeId)
                .stream()
                .map(ScheduleResponseDto::new)
                .collect(Collectors.toList());

        return ResponseEntity.ok(schedules);
    }
}