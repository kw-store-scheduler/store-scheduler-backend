package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.service.ShiftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
public class ShiftController {

    private final ShiftService shiftService;

    // 관리자: 근무 시간대 등록
    @PostMapping("/api/stores/{storeId}/shifts")
    public ResponseEntity<ShiftResponse> createShift(
            @PathVariable Long storeId,
            @Valid @RequestBody ShiftRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Shift shift = shiftService.registerShift(
                storeId,
                request.getName(),
                request.getStartTime(),
                request.getEndTime(),
                request.getRequiredStaff(),
                request.getDayOfWeek(),
                userDetails.getUsername());
        return ResponseEntity.ok(ShiftResponse.from(shift));
    }

    // 관리자/직원: 매장 근무 시간대 조회
    @GetMapping("/api/stores/{storeId}/shifts")
    public ResponseEntity<List<ShiftResponse>> getShifts(@PathVariable Long storeId) {
        List<ShiftResponse> result = shiftService.findByStore(storeId).stream()
                .map(ShiftResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Data
    static class ShiftRequest {
        @NotBlank(message = "근무 시간대 이름을 입력해주세요.")
        private String name;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;

        @NotNull(message = "필요 인원을 입력해주세요.")
        @Min(value = 1, message = "필요 인원은 1명 이상이어야 합니다.")
        private Integer requiredStaff;

        private DayOfWeek dayOfWeek;
    }

    @Data
    @AllArgsConstructor
    static class ShiftResponse {
        private Long id;
        private String name;
        private LocalTime startTime;
        private LocalTime endTime;
        private Integer requiredStaff;
        private DayOfWeek dayOfWeek;

        static ShiftResponse from(Shift s) {
            return new ShiftResponse(s.getId(), s.getName(), s.getStartTime(),
                    s.getEndTime(), s.getRequiredStaff(), s.getDayOfWeek());
        }
    }
}
