package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.service.SubstituteFinder;
import com.example.store_scheduler_backend.service.SwapRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "결원·대타 API", description = "결원 요청, 대타 추천·지정·수락")
public class SwapRequestController {

    private final SwapRequestService swapRequestService;

    // 직원: 본인 근무 결원 요청
    @PostMapping("/api/stores/{storeId}/schedules/{scheduleId}/swap-requests")
    public ResponseEntity<SwapRequestResponse> request(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @RequestBody(required = false) SwapRequestBody body,
            @AuthenticationPrincipal UserDetails userDetails) {
        String reason = body != null ? body.getReason() : null;
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.request(storeId, scheduleId, reason, userDetails.getUsername())));
    }

    // 관리자: 결원 요청 목록
    @GetMapping("/api/stores/{storeId}/swap-requests")
    public ResponseEntity<List<SwapRequestResponse>> getRequests(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(swapRequestService.findByStore(storeId, userDetails.getUsername()).stream()
                .map(SwapRequestResponse::from)
                .toList());
    }

    @Operation(summary = "대타 추천", description = "근무 가능한 직원을 이번 주 근무 시간이 적은 순으로, 불가능한 직원은 사유와 함께 반환합니다.")
    @GetMapping("/api/stores/{storeId}/swap-requests/{requestId}/candidates")
    public ResponseEntity<List<CandidateResponse>> getCandidates(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(swapRequestService.findCandidates(storeId, requestId, userDetails.getUsername()).stream()
                .map(CandidateResponse::from)
                .toList());
    }

    // 관리자: 대타 지정
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/offer")
    public ResponseEntity<SwapRequestResponse> offer(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @Valid @RequestBody OfferRequest body,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.offer(storeId, requestId, body.getEmployeeId(), userDetails.getUsername())));
    }

    // 대타 직원: 수락
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/accept")
    public ResponseEntity<SwapRequestResponse> accept(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.accept(storeId, requestId, userDetails.getUsername())));
    }

    // 대타 직원: 거절
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/decline")
    public ResponseEntity<SwapRequestResponse> decline(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.decline(storeId, requestId, userDetails.getUsername())));
    }

    // 요청 직원: 취소
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/cancel")
    public ResponseEntity<SwapRequestResponse> cancel(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.cancel(storeId, requestId, userDetails.getUsername())));
    }

    @Data
    static class SwapRequestBody {
        private String reason;
    }

    @Data
    static class OfferRequest {
        @NotNull(message = "대타로 지정할 직원을 선택해주세요.")
        private Long employeeId;
    }

    @Data
    @AllArgsConstructor
    static class SwapRequestResponse {
        private Long id;
        private Long scheduleId;
        private LocalDate workDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private Long requesterId;
        private String requesterName;
        private Long substituteId;
        private String substituteName;
        private String reason;
        private String status;

        static SwapRequestResponse from(SwapRequest r) {
            return new SwapRequestResponse(
                    r.getId(),
                    r.getSchedule() != null ? r.getSchedule().getId() : null,
                    r.getWorkDate(),
                    r.getStartTime(),
                    r.getEndTime(),
                    r.getRequester().getId(),
                    r.getRequester().getName(),
                    r.getSubstitute() != null ? r.getSubstitute().getId() : null,
                    r.getSubstitute() != null ? r.getSubstitute().getName() : null,
                    r.getReason(),
                    r.getStatus().name());
        }
    }

    @Data
    @AllArgsConstructor
    static class CandidateResponse {
        private Long employeeId;
        private String name;
        private boolean eligible;
        private int weeklyHours;
        private List<String> reasons;

        static CandidateResponse from(SubstituteFinder.Candidate c) {
            return new CandidateResponse(c.employee().getId(), c.employee().getName(),
                    c.eligible(), c.weeklyHours(), c.reasons());
        }
    }
}
