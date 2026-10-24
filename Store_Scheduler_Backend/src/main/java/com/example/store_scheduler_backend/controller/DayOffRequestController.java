package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.service.DayOffRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "휴무 신청 API", description = "날짜별 필수·희망 휴무 신청")
public class DayOffRequestController {

    private final DayOffRequestService dayOffRequestService;

    // 직원: 휴무 신청
    @PostMapping("/api/stores/{storeId}/day-offs")
    public ResponseEntity<DayOffResponse> request(
            @PathVariable Long storeId,
            @Valid @RequestBody DayOffRequestBody body,
            @AuthenticationPrincipal UserDetails userDetails) {
        DayOffRequest saved = dayOffRequestService.request(
                storeId, body.getDate(), body.getType(), body.getReason(), userDetails.getUsername());
        return ResponseEntity.ok(DayOffResponse.from(saved));
    }

    // 직원: 본인 휴무 신청 목록
    @GetMapping("/api/stores/{storeId}/day-offs/my")
    public ResponseEntity<List<DayOffResponse>> getMine(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(dayOffRequestService.findMine(storeId, userDetails.getUsername()).stream()
                .map(DayOffResponse::from)
                .toList());
    }

    // 관리자: 기간 내 매장 휴무 신청 목록
    @GetMapping("/api/stores/{storeId}/day-offs")
    public ResponseEntity<List<DayOffResponse>> getByStore(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(dayOffRequestService.findByStore(storeId, from, to, userDetails.getUsername()).stream()
                .map(DayOffResponse::from)
                .toList());
    }

    // 직원: 본인 휴무 신청 취소
    @DeleteMapping("/api/stores/{storeId}/day-offs/{requestId}")
    public ResponseEntity<Void> cancel(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        dayOffRequestService.cancel(storeId, requestId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Data
    static class DayOffRequestBody {
        @NotNull(message = "휴무 날짜를 입력해주세요.")
        private LocalDate date;

        @NotNull(message = "휴무 종류(MUST 또는 PREFER)를 입력해주세요.")
        private DayOffType type;

        private String reason;
    }

    @Data
    @AllArgsConstructor
    static class DayOffResponse {
        private Long id;
        private Long employeeId;
        private String employeeName;
        private LocalDate date;
        private String type;
        private String reason;

        static DayOffResponse from(DayOffRequest r) {
            return new DayOffResponse(r.getId(), r.getEmployee().getId(), r.getEmployee().getName(),
                    r.getDate(), r.getType().name(), r.getReason());
        }
    }
}
