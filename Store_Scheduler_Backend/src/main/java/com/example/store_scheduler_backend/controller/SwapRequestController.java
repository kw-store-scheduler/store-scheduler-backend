package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.AbsenceReason;
import com.example.store_scheduler_backend.domain.SwapOffer;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.service.OpenShiftService;
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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "결원·대타 API", description = "결원 신고, 동료에게 대타 요청(먼저 수락한 1명), 사장님 승인·직접 배정, 게시판 올리기")
@RequiredArgsConstructor
public class SwapRequestController {

    private final SwapRequestService swapRequestService;

    @Operation(summary = "결원 신고", description = "reasonCategory: SICK(병가)·FAMILY_EVENT(경조사)·PERSONAL(개인 사정)·OTHER. partialFrom을 주면 그 시각부터만 빠집니다. 사유(reason)는 사장님만 봅니다.")
    @PostMapping("/api/stores/{storeId}/schedules/{scheduleId}/swap-requests")
    public ResponseEntity<SwapRequestResponse> request(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @RequestBody(required = false) SwapRequestBody body,
            @AuthenticationPrincipal UserDetails userDetails) {
        SwapRequestBody b = body != null ? body : new SwapRequestBody();
        return ResponseEntity.ok(SwapRequestResponse.from(swapRequestService.request(storeId, scheduleId, b.getReason(),
                b.getReasonCategory(), b.getPartialFrom(), userDetails.getUsername())));
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

    @Operation(summary = "대타 후보", description = "사장님과 요청한 직원이 봅니다. 가능한 직원을 이번 주 근무 시간이 적은 순으로, 불가능한 직원은 사유와 함께. contactStatus는 대타 요청·게시판 지원 상태입니다.")
    @GetMapping("/api/stores/{storeId}/swap-requests/{requestId}/candidates")
    public ResponseEntity<List<CandidateResponse>> getCandidates(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails.getUsername();
        boolean owner = swapRequestService.isOwner(storeId, username);
        Map<Long, SwapRequestService.Contact> contacts = swapRequestService.findCandidateContacts(storeId, requestId, username);
        return ResponseEntity.ok(swapRequestService.findCandidates(storeId, requestId, username).stream()
                .map(c -> CandidateResponse.from(c, contacts.get(c.employee().getId()), owner))
                .toList());
    }

    @Operation(summary = "동료들에게 대타 요청", description = "요청한 직원이나 사장님이 고른 동료들에게 동시에 보냅니다. 먼저 수락한 1명으로 정해지고, 직원이 보낸 요청은 사장님 승인을 거칩니다. 30분 안에 아무도 수락하지 않으면 빈 근무 게시판으로 넘어갑니다.")
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/offers")
    public ResponseEntity<SwapRequestResponse> sendOffers(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @RequestBody OffersRequest body,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.sendOffers(storeId, requestId, body.getEmployeeIds(), userDetails.getUsername())));
    }

    // 관리자: 한 명에게 대타 지정 (수락하면 바로 확정)
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/offer")
    public ResponseEntity<SwapRequestResponse> offer(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @Valid @RequestBody OfferRequest body,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.offer(storeId, requestId, body.getEmployeeId(), userDetails.getUsername())));
    }

    @Operation(summary = "후보 전체에게 대타 일괄 요청", description = "대신할 수 있는 직원 모두에게 보냅니다. 사장님이 보낸 요청이라 먼저 수락한 사람으로 바로 확정됩니다.")
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/broadcast")
    public ResponseEntity<BroadcastResponse> broadcast(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(new BroadcastResponse(null,
                swapRequestService.broadcast(storeId, requestId, userDetails.getUsername())));
    }

    // 요청한 직원이나 사장님: 빈 근무 게시판에 올리기
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/board")
    public ResponseEntity<BroadcastResponse> postToBoard(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        OpenShiftService.Broadcast result = swapRequestService.postToBoard(storeId, requestId, userDetails.getUsername());
        return ResponseEntity.ok(new BroadcastResponse(result.openShift().getId(), result.notifiedCount()));
    }

    // 관리자: 직원끼리 구한 대타 승인
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/approve")
    public ResponseEntity<SwapRequestResponse> approve(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.approve(storeId, requestId, userDetails.getUsername())));
    }

    // 관리자: 직원끼리 구한 대타를 받아들이지 않음
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/reject-substitute")
    public ResponseEntity<SwapRequestResponse> rejectSubstitute(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.rejectSubstitute(storeId, requestId, userDetails.getUsername())));
    }

    // 관리자: 직접 배정 (요청·수락 없이 바로 확정)
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/assign")
    public ResponseEntity<SwapRequestResponse> assign(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @Valid @RequestBody OfferRequest body,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.assignDirectly(storeId, requestId, body.getEmployeeId(), userDetails.getUsername())));
    }

    @Operation(summary = "나에게 온 대타 요청", description = "예상 급여, 이번 주 근무일 변화, 나에게 온 이유, 같이 요청받은 인원")
    @GetMapping("/api/stores/{storeId}/swap-offers/my")
    public ResponseEntity<List<OfferResponse>> getMyOffers(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(swapRequestService.findMyOffers(storeId, userDetails.getUsername()).stream()
                .map(OfferResponse::from).toList());
    }

    @PostMapping("/api/stores/{storeId}/swap-offers/{offerId}/accept")
    public ResponseEntity<SwapRequestResponse> acceptOffer(
            @PathVariable Long storeId,
            @PathVariable Long offerId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.respond(storeId, offerId, true, userDetails.getUsername())));
    }

    @PostMapping("/api/stores/{storeId}/swap-offers/{offerId}/decline")
    public ResponseEntity<SwapRequestResponse> declineOffer(
            @PathVariable Long storeId,
            @PathVariable Long offerId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.respond(storeId, offerId, false, userDetails.getUsername())));
    }

    // 대타 직원: 결원 요청 번호로 수락 (기존 방식)
    @PostMapping("/api/stores/{storeId}/swap-requests/{requestId}/accept")
    public ResponseEntity<SwapRequestResponse> accept(
            @PathVariable Long storeId,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(SwapRequestResponse.from(
                swapRequestService.accept(storeId, requestId, userDetails.getUsername())));
    }

    // 대타 직원: 결원 요청 번호로 거절 (기존 방식)
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
        private AbsenceReason reasonCategory;
        private LocalTime partialFrom;
    }

    @Data
    static class OfferRequest {
        @NotNull(message = "대타로 지정할 직원을 선택해주세요.")
        private Long employeeId;
    }

    @Data
    static class OffersRequest {
        private List<Long> employeeIds;
    }

    record BroadcastResponse(Long openShiftId, int notifiedCount) {
    }

    @Data
    @AllArgsConstructor
    static class SwapRequestResponse {
        private Long id;
        private Long scheduleId;
        private LocalDate workDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private LocalTime partialFrom;
        private Long requesterId;
        private String requesterName;
        private Long substituteId;
        private String substituteName;
        private String reason;
        private AbsenceReason reasonCategory;
        private String status;

        static SwapRequestResponse from(SwapRequest r) {
            return new SwapRequestResponse(
                    r.getId(),
                    r.getSchedule() != null ? r.getSchedule().getId() : null,
                    r.getWorkDate(),
                    r.getStartTime(),
                    r.getEndTime(),
                    r.getPartialFrom(),
                    r.getRequester().getId(),
                    r.getRequester().getName(),
                    r.getSubstitute() != null ? r.getSubstitute().getId() : null,
                    r.getSubstitute() != null ? r.getSubstitute().getName() : null,
                    r.getReason(),
                    r.getReasonCategory(),
                    r.getStatus().name());
        }
    }

    // contactStatus: SENT(확인 중)·ACCEPTED·DECLINED·EXPIRED·CLOSED·PENDING_APPROVAL 또는 게시판 지원 상태, 연락 전이면 null
    // waitingMinutes: 확인 중이면 보낸 뒤 지난 분 ("응답 없음 12분")
    @Data
    @AllArgsConstructor
    static class CandidateResponse {
        private Long employeeId;
        private String name;
        private boolean eligible;
        private int weeklyHours;
        private List<String> coveredSkills;
        private List<String> reasons;
        private String contactStatus;
        private Long waitingMinutes;

        // 동료가 보는 화면에는 다른 직원의 시급을 빼고 보여준다
        static CandidateResponse from(SubstituteFinder.Candidate c, SwapRequestService.Contact contact, boolean owner) {
            List<String> reasons = owner ? c.reasons() : c.reasons().stream().filter(r -> !r.startsWith("시급")).toList();
            Long waiting = contact != null && "SENT".equals(contact.status()) && contact.sentAt() != null
                    ? Duration.between(contact.sentAt(), LocalDateTime.now()).toMinutes() : null;
            return new CandidateResponse(c.employee().getId(), c.employee().getDisplayName(), c.eligible(), c.weeklyHours(),
                    c.coveredSkills(), reasons, contact != null ? contact.status() : null, waiting);
        }
    }

    record OfferResponse(Long offerId, Long requestId, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                         String requesterName, long expectedPay, int weekDaysBefore, int weekDaysAfter, List<String> reasons,
                         long sentCount, boolean needsOwnerApproval) {
        static OfferResponse from(SwapRequestService.OfferView v) {
            SwapOffer o = v.offer();
            SwapRequest r = o.getSwapRequest();
            return new OfferResponse(o.getId(), r.getId(), r.getWorkDate(), r.getStartTime(), r.getEndTime(),
                    r.getRequester().getDisplayName(), v.expectedPay(), v.weekDaysBefore(), v.weekDaysAfter(), v.reasons(),
                    v.sentCount(), !o.isSentByOwner());
        }
    }
}
