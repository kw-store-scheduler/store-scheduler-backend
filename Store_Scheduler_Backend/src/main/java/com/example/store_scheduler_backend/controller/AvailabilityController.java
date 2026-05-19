package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Availability;
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

    /**
     * 가용 시간 등록 API
     */
    @PostMapping
    public ResponseEntity<Long> createAvailability(@RequestBody Availability availability) {
        Long availabilityId = availabilityService.registerAvailability(availability);
        return ResponseEntity.ok(availabilityId);
    }

    /**
     * 가용 시간 목록 전체 조회 API (DTO 적용 버전으로 순환 참조 선제 방어)
     */
    @GetMapping
    public ResponseEntity<List<AvailabilityResponseDto>> getAllAvailabilities() {
        // 서비스의 전체 조회 메서드와 정확히 매핑을 맞추었습니다.
        List<Availability> availabilities = availabilityService.findAvailabilities();

        // 엔티티를 직접 반환하지 않고, 알바생 이름만 쏙 빼내어 DTO로 변환하여 반환합니다.
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

    /**
     * 가용 시간 외부 전송용 안전한 가방 (DTO)
     */
    @Data
    @AllArgsConstructor
    static class AvailabilityResponseDto {
        private Long id;
        private String employeeName; // 연관된 직원 객체 통째로가 아닌, 이름만 반환하여 루프를 끊습니다.
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
    }
}