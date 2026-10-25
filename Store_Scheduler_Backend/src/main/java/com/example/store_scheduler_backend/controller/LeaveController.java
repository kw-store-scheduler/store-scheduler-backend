package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.LeaveRequest;
import com.example.store_scheduler_backend.domain.LeaveStatus;
import com.example.store_scheduler_backend.service.LeaveService;
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
import java.util.List;

@RestController
@Tag(name = "연차 API", description = "정규직 연차 신청·승인과 남은 일수")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    @Operation(summary = "내 연차", description = "근무 시작일 기준 발생 일수, 사용(승인 대기 포함), 남은 일수와 신청 목록. 정규직이 아니면 balance는 null")
    @GetMapping("/api/stores/{storeId}/leaves/my")
    public ResponseEntity<MineResponse> getMine(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        LeaveService.Mine mine = leaveService.findMine(storeId, userDetails.getUsername());
        return ResponseEntity.ok(new MineResponse(BalanceResponse.from(mine.balance()),
                mine.requests().stream().map(l -> LeaveResponse.from(l, null)).toList()));
    }

    // 직원(정규직): 연차 신청
    @PostMapping("/api/stores/{storeId}/leaves")
    public ResponseEntity<LeaveResponse> request(
            @PathVariable Long storeId,
            @Valid @RequestBody LeaveRequestBody body,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(LeaveResponse.from(
                leaveService.request(storeId, body.getDate(), body.getReason(), userDetails.getUsername()), null));
    }

    // 관리자: 승인 대기 연차
    @GetMapping("/api/stores/{storeId}/leaves")
    public ResponseEntity<List<LeaveResponse>> getPending(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(leaveService.findPending(storeId, userDetails.getUsername()).stream()
                .map(l -> LeaveResponse.from(l, leaveService.hasSchedule(l))).toList());
    }

    @Operation(summary = "연차 승인", description = "그날을 필수 휴무로 등록합니다. 이미 확정된 근무가 있으면 hasSchedule=true이니 대타를 구하거나 근무표를 다시 만들어주세요.")
    @PostMapping("/api/stores/{storeId}/leaves/{leaveId}/approve")
    public ResponseEntity<LeaveResponse> approve(
            @PathVariable Long storeId,
            @PathVariable Long leaveId,
            @AuthenticationPrincipal UserDetails userDetails) {
        LeaveRequest leave = leaveService.approve(storeId, leaveId, userDetails.getUsername());
        return ResponseEntity.ok(LeaveResponse.from(leave, leaveService.hasSchedule(leave)));
    }

    @PostMapping("/api/stores/{storeId}/leaves/{leaveId}/reject")
    public ResponseEntity<LeaveResponse> reject(
            @PathVariable Long storeId,
            @PathVariable Long leaveId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(LeaveResponse.from(leaveService.reject(storeId, leaveId, userDetails.getUsername()), null));
    }

    @Data
    static class LeaveRequestBody {
        @NotNull(message = "연차 날짜를 입력해주세요.")
        private LocalDate date;
        private String reason;
    }

    record BalanceResponse(LocalDate leaveYearStart, int entitled, int used, int remaining) {
        static BalanceResponse from(LeaveService.Balance b) {
            return b == null ? null : new BalanceResponse(b.leaveYearStart(), b.entitled(), b.used(), b.remaining());
        }
    }

    record LeaveResponse(Long id, Long employeeId, String employeeName, LocalDate date, String reason, LeaveStatus status,
                         Boolean hasSchedule) {
        static LeaveResponse from(LeaveRequest l, Boolean hasSchedule) {
            return new LeaveResponse(l.getId(), l.getEmployee().getId(), l.getEmployee().getDisplayName(), l.getDate(),
                    l.getReason(), l.getStatus(), hasSchedule);
        }
    }

    record MineResponse(BalanceResponse balance, List<LeaveResponse> requests) {
    }
}
