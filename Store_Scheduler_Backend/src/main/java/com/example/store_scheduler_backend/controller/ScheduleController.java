package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.dto.ScheduleResponseDto;
import com.example.store_scheduler_backend.service.AvailabilityService;
import com.example.store_scheduler_backend.service.EmployeeService;
import com.example.store_scheduler_backend.service.ScheduleAutomationService;
import com.example.store_scheduler_backend.service.ScheduleService;
import com.example.store_scheduler_backend.service.ShiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stores/{storeId}/schedules")
@RequiredArgsConstructor
@Tag(name = "스케줄 API", description = "스케줄 자동 생성 및 조회")
public class ScheduleController {

    private final EmployeeService employeeService;
    private final AvailabilityService availabilityService;
    private final ShiftService shiftService;
    private final ScheduleAutomationService automationService;
    private final ScheduleService scheduleService;

    @Operation(summary = "스케줄 자동 생성", description = "매장의 승인된 직원과 가용 시간을 기반으로 최적 스케줄을 생성합니다.")
    @PostMapping("/automate")
    public ResponseEntity<Map<String, Object>> automateSchedule(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<Employee> employees = employeeService.findApprovedByStore(storeId);
        List<Availability> availabilities = availabilityService.findByStore(storeId);
        List<Shift> shifts = shiftService.findByStore(storeId);

        if (shifts.isEmpty() || employees.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "승인된 직원 또는 등록된 근무 시간대(Shift)가 없습니다."));
        }

        List<Map<String, Object>> empConfigList = new ArrayList<>();
        for (Employee emp : employees) {
            Map<String, Object> empMap = new HashMap<>();
            empMap.put("id", "emp_" + emp.getId());
            empMap.put("name", emp.getName());
            empMap.put("hourly_wage", emp.getHourlyWage());

            List<Integer> availableDays = availabilities.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                    .map(a -> a.getDayOfWeek().getValue() - 1)
                    .distinct()
                    .collect(Collectors.toList());
            empMap.put("available_days", availableDays);

            List<Integer> preferredShifts = java.util.stream.IntStream.range(0, shifts.size())
                    .boxed().collect(Collectors.toList());
            empMap.put("preferred_shifts", preferredShifts);
            empConfigList.add(empMap);
        }

        List<Map<String, Object>> shiftConfigList = new ArrayList<>();
        List<Integer> targetStaffList = new ArrayList<>();
        List<Integer> minStaffList = new ArrayList<>();

        for (Shift shift : shifts) {
            long hours = java.time.Duration.between(shift.getStartTime(), shift.getEndTime()).toHours();
            if (hours <= 0) hours = 4;
            boolean isNight = shift.getEndTime().isAfter(LocalTime.of(22, 0)) ||
                    shift.getStartTime().isBefore(LocalTime.of(6, 0));
            shiftConfigList.add(Map.of("name", shift.getName(), "hours", (int) hours, "is_night", isNight));
            targetStaffList.add(shift.getRequiredStaff());
            minStaffList.add(1);
        }

        int baseHourly = employees.stream()
                .mapToInt(e -> e.getHourlyWage() != null ? e.getHourlyWage() : 0)
                .sum() / employees.size();

        Map<String, Object> config = new HashMap<>();
        config.put("employees", empConfigList);
        config.put("shifts", shiftConfigList);
        config.put("target_staff", targetStaffList);
        config.put("min_staff", minStaffList);
        config.put("base_hourly", baseHourly);
        config.put("night_bonus", 0.5);
        config.put("time_limit", 10);

        return ResponseEntity.ok(automationService.runOptimization(storeId, config));
    }

    // 관리자/직원: 매장 전체 근무표 조회
    @GetMapping
    public ResponseEntity<List<ScheduleResponseDto>> getSchedules(@PathVariable Long storeId) {
        List<ScheduleResponseDto> result = scheduleService.findByStore(storeId).stream()
                .map(ScheduleResponseDto::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 직원: 본인 근무표 조회
    @GetMapping("/my")
    public ResponseEntity<List<ScheduleResponseDto>> getMySchedules(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<ScheduleResponseDto> result = scheduleService.findMySchedules(storeId, userDetails.getUsername()).stream()
                .map(ScheduleResponseDto::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 스케줄 단건 수정
    @PutMapping("/{scheduleId}")
    public ResponseEntity<ScheduleResponseDto> updateSchedule(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Schedule updated = scheduleService.update(storeId, scheduleId, request.getWorkDate(), request.getStartTime(), request.getEndTime(), userDetails.getUsername());
        return ResponseEntity.ok(new ScheduleResponseDto(updated));
    }

    // 관리자: 스케줄 단건 삭제
    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal UserDetails userDetails) {
        scheduleService.delete(storeId, scheduleId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Data
    static class ScheduleUpdateRequest {
        @NotNull(message = "근무 날짜를 입력해주세요.")
        private LocalDate workDate;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;
    }
}
