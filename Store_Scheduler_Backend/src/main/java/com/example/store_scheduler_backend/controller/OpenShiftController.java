package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.ApplicationStatus;
import com.example.store_scheduler_backend.domain.OpenShift;
import com.example.store_scheduler_backend.domain.OpenShiftKind;
import com.example.store_scheduler_backend.domain.ShiftApplication;
import com.example.store_scheduler_backend.service.OpenShiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@Tag(name = "빈 근무 게시판 API", description = "결원·추가 모집 근무에 직원이 지원하고 점주가 승인")
@RequiredArgsConstructor
public class OpenShiftController {

    private final OpenShiftService openShiftService;

    @Operation(summary = "빈 근무 목록", description = "오늘 이후 열린 글. 직원이 보면 지원 가능 여부(eligible)와 불가 사유, 예상 수당, 내 지원 상태, 이번 주 내 근무가 함께 옵니다.")
    @GetMapping("/api/stores/{storeId}/open-shifts")
    public ResponseEntity<BoardResponse> getBoard(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        OpenShiftService.Board board = openShiftService.getBoard(storeId, userDetails.getUsername());
        return ResponseEntity.ok(new BoardResponse(
                board.items().stream().map(BoardItemResponse::from).toList(), board.myWeekDays(), board.myWeekHours()));
    }

    // 관리자: 추가 인원 모집 글 (단체 예약, 피크 시간 등)
    @PostMapping("/api/stores/{storeId}/open-shifts")
    public ResponseEntity<BoardItemResponse> createExtra(
            @PathVariable Long storeId,
            @Valid @RequestBody ExtraRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        OpenShift openShift = openShiftService.createExtra(storeId, request.getWorkDate(), request.getStartTime(),
                request.getEndTime(), request.getTitle(), request.getNote(),
                request.getHeadcount() != null ? request.getHeadcount() : 1, userDetails.getUsername());
        return ResponseEntity.ok(BoardItemResponse.from(new OpenShiftService.BoardItem(openShift, 0, null, null, null, null)));
    }

    // 관리자: 추가 모집 글 내리기
    @DeleteMapping("/api/stores/{storeId}/open-shifts/{openShiftId}")
    public ResponseEntity<Void> cancelExtra(
            @PathVariable Long storeId,
            @PathVariable Long openShiftId,
            @AuthenticationPrincipal UserDetails userDetails) {
        openShiftService.cancelExtra(storeId, openShiftId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // 직원: 지원 (근무 규칙을 통과해야 함)
    @PostMapping("/api/stores/{storeId}/open-shifts/{openShiftId}/applications")
    public ResponseEntity<ApplicationResponse> apply(
            @PathVariable Long storeId,
            @PathVariable Long openShiftId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ApplicationResponse.from(openShiftService.apply(storeId, openShiftId, userDetails.getUsername())));
    }

    // 직원: 지원 취소
    @DeleteMapping("/api/stores/{storeId}/open-shifts/{openShiftId}/applications/my")
    public ResponseEntity<Void> withdraw(
            @PathVariable Long storeId,
            @PathVariable Long openShiftId,
            @AuthenticationPrincipal UserDetails userDetails) {
        openShiftService.withdraw(storeId, openShiftId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // 관리자: 지원자 목록
    @GetMapping("/api/stores/{storeId}/open-shifts/{openShiftId}/applications")
    public ResponseEntity<List<ApplicationResponse>> getApplications(
            @PathVariable Long storeId,
            @PathVariable Long openShiftId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(openShiftService.findApplications(storeId, openShiftId, userDetails.getUsername()).stream()
                .map(ApplicationResponse::from).toList());
    }

    @Operation(summary = "지원 승인", description = "결원이면 근무 담당자를 지원자로 바꾸고, 추가 모집이면 지원자 근무를 새로 만듭니다. 모집 인원이 차면 나머지 지원은 거절됩니다.")
    @PostMapping("/api/stores/{storeId}/open-shifts/{openShiftId}/applications/{applicationId}/approve")
    public ResponseEntity<ApplicationResponse> approve(
            @PathVariable Long storeId,
            @PathVariable Long openShiftId,
            @PathVariable Long applicationId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ApplicationResponse.from(
                openShiftService.approve(storeId, openShiftId, applicationId, userDetails.getUsername())));
    }

    @Data
    static class ExtraRequest {
        @NotNull(message = "날짜를 입력해주세요.")
        private LocalDate workDate;
        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;
        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;
        @NotBlank(message = "제목을 입력해주세요.")
        private String title;
        private String note;
        // 생략하면 1명
        private Integer headcount;
    }

    record BoardItemResponse(Long id, OpenShiftKind kind, Long swapRequestId, LocalDate workDate, LocalTime startTime,
                             LocalTime endTime, String title, String note, int headcount, int applicantCount,
                             Boolean eligible, String blocker, Long expectedPay, ApplicationStatus myStatus) {
        static BoardItemResponse from(OpenShiftService.BoardItem item) {
            OpenShift o = item.openShift();
            return new BoardItemResponse(o.getId(), o.getKind(), o.getSwapRequest() != null ? o.getSwapRequest().getId() : null,
                    o.getWorkDate(), o.getStartTime(), o.getEndTime(), o.getTitle(), o.getNote(), o.getHeadcount(),
                    item.applicantCount(), item.eligible(), item.blocker(), item.expectedPay(), item.myStatus());
        }
    }

    record BoardResponse(List<BoardItemResponse> items, Integer myWeekDays, Integer myWeekHours) {
    }

    record ApplicationResponse(Long id, Long openShiftId, Long employeeId, String employeeName, ApplicationStatus status,
                               LocalDateTime appliedAt) {
        static ApplicationResponse from(ShiftApplication a) {
            return new ApplicationResponse(a.getId(), a.getOpenShift().getId(), a.getEmployee().getId(),
                    a.getEmployee().getDisplayName(), a.getStatus(), a.getAppliedAt());
        }
    }
}
