package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.service.AvailabilityService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/availabilities")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;
    private final EmployeeRepository employeeRepository;

    /**
     * 가용 시간 등록 API (500 에러 방지용 직원 객체 매핑 추가)
     */
    @PostMapping
    public ResponseEntity<Long> createAvailability(@RequestBody Availability availability) {

        if (availability.getEmployee() != null && availability.getEmployee().getId() != null) {
            Employee employee = employeeRepository.findById(availability.getEmployee().getId())
                    .orElseThrow(() -> new RuntimeException("해당 직원을 찾을 수 없습니다."));
            availability.setEmployee(employee);
        }

        Long availabilityId = availabilityService.registerAvailability(availability);
        return ResponseEntity.ok(availabilityId);
    }

    /**
     * 가용 시간 목록 전체 조회 API
     */
    @GetMapping
    public ResponseEntity<List<AvailabilityResponseDto>> getAllAvailabilities() {
        List<Availability> availabilities = availabilityService.findAvailabilities();

        List<AvailabilityResponseDto> result = availabilities.stream()
                .map(a -> new AvailabilityResponseDto(
                        a.getId(),
                        a.getEmployee() != null ? a.getEmployee().getName() : "기록 없음",
                        a.getDayOfWeek(),
                        a.getStartTime(),
                        a.getEndTime()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @Data
    @AllArgsConstructor
    static class AvailabilityResponseDto {
        private Long id;
        private String employeeName;
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
    }
}