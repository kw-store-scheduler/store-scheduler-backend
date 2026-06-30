package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.service.AvailabilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    // 직원: 가용 시간 등록
    @PostMapping("/api/availabilities")
    public ResponseEntity<AvailabilityResponse> create(
            @Valid @RequestBody AvailabilityRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Availability saved = availabilityService.register(
                request.getStoreId(),
                request.getDayOfWeek(),
                request.getStartTime(),
                request.getEndTime(),
                userDetails.getUsername());
        return ResponseEntity.ok(AvailabilityResponse.from(saved));
    }

    // 직원: 본인 가용 시간 전체 조회
    @GetMapping("/api/availabilities/my")
    public ResponseEntity<List<AvailabilityResponse>> getMyAvailabilities(
            @AuthenticationPrincipal UserDetails userDetails) {
        List<AvailabilityResponse> result = availabilityService.findMyAvailabilities(userDetails.getUsername())
                .stream().map(AvailabilityResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 직원: 본인 가용 시간 수정
    @PutMapping("/api/availabilities/{id}")
    public ResponseEntity<AvailabilityResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AvailabilityUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Availability updated = availabilityService.update(id, request.getDayOfWeek(), request.getStartTime(), request.getEndTime(), userDetails.getUsername());
        return ResponseEntity.ok(AvailabilityResponse.from(updated));
    }

    // 직원: 본인 가용 시간 삭제
    @DeleteMapping("/api/availabilities/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        availabilityService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // 관리자: 매장 전체 직원 가용 시간 조회
    @GetMapping("/api/stores/{storeId}/availabilities")
    public ResponseEntity<List<AvailabilityResponse>> getByStore(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<AvailabilityResponse> result = availabilityService.findByStore(storeId)
                .stream().map(AvailabilityResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Data
    static class AvailabilityUpdateRequest {
        @NotNull(message = "요일을 입력해주세요.")
        private DayOfWeek dayOfWeek;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;
    }

    @Data
    static class AvailabilityRequest {
        @NotNull(message = "매장 ID를 입력해주세요.")
        private Long storeId;

        @NotNull(message = "요일을 입력해주세요.")
        private DayOfWeek dayOfWeek;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;
    }

    @Data
    @AllArgsConstructor
    static class AvailabilityResponse {
        private Long id;
        private Long employeeId;
        private String employeeName;
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;

        static AvailabilityResponse from(Availability a) {
            return new AvailabilityResponse(
                    a.getId(),
                    a.getEmployee().getId(),
                    a.getEmployee().getName(),
                    a.getDayOfWeek(),
                    a.getStartTime(),
                    a.getEndTime()
            );
        }
    }
}
