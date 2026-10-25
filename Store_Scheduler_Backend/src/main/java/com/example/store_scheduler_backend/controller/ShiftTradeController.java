package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ShiftTrade;
import com.example.store_scheduler_backend.domain.TradeStatus;
import com.example.store_scheduler_backend.domain.TradeType;
import com.example.store_scheduler_backend.service.ShiftTradeService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@Tag(name = "근무 바꾸기 API", description = "근무 넘기기·맞교환. 근무 48시간 전까지, 동료 수락 → 사장님 승인")
@RequiredArgsConstructor
public class ShiftTradeController {

    private final ShiftTradeService shiftTradeService;

    @Operation(summary = "바꿀 수 있는 선택지", description = "handover는 넘겨받을 수 있는 동료, exchange는 맞바꿀 수 있는 동료의 근무(앞뒤 7일). 안 되는 선택지는 사유와 함께")
    @GetMapping("/api/stores/{storeId}/schedules/{scheduleId}/trade-options")
    public ResponseEntity<OptionsResponse> getOptions(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal UserDetails userDetails) {
        ShiftTradeService.Options options = shiftTradeService.findOptions(storeId, scheduleId, userDetails.getUsername());
        return ResponseEntity.ok(new OptionsResponse(
                options.handover().stream().map(OptionResponse::from).toList(),
                options.exchange().stream().map(OptionResponse::from).toList()));
    }

    // 직원: 넘기기(targetEmployeeId) 또는 맞교환(targetScheduleId) 요청
    @PostMapping("/api/stores/{storeId}/schedules/{scheduleId}/trades")
    public ResponseEntity<TradeResponse> request(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody TradeRequest body,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TradeResponse.from(shiftTradeService.request(storeId, scheduleId, body.getType(),
                body.getTargetEmployeeId(), body.getTargetScheduleId(), Boolean.TRUE.equals(body.getRecurring()),
                userDetails.getUsername())));
    }

    // 직원: 내가 보낸·받은 요청
    @GetMapping("/api/stores/{storeId}/trades/my")
    public ResponseEntity<List<TradeResponse>> getMine(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(shiftTradeService.findMine(storeId, userDetails.getUsername()).stream()
                .map(TradeResponse::from).toList());
    }

    // 관리자: 진행 중인 요청
    @GetMapping("/api/stores/{storeId}/trades")
    public ResponseEntity<List<TradeResponse>> getOpen(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(shiftTradeService.findOpen(storeId, userDetails.getUsername()).stream()
                .map(TradeResponse::from).toList());
    }

    @PostMapping("/api/stores/{storeId}/trades/{tradeId}/accept")
    public ResponseEntity<TradeResponse> accept(@PathVariable Long storeId, @PathVariable Long tradeId,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TradeResponse.from(shiftTradeService.respond(storeId, tradeId, true, userDetails.getUsername())));
    }

    @PostMapping("/api/stores/{storeId}/trades/{tradeId}/decline")
    public ResponseEntity<TradeResponse> decline(@PathVariable Long storeId, @PathVariable Long tradeId,
                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TradeResponse.from(shiftTradeService.respond(storeId, tradeId, false, userDetails.getUsername())));
    }

    @PostMapping("/api/stores/{storeId}/trades/{tradeId}/approve")
    public ResponseEntity<TradeResponse> approve(@PathVariable Long storeId, @PathVariable Long tradeId,
                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TradeResponse.from(shiftTradeService.approve(storeId, tradeId, userDetails.getUsername())));
    }

    @PostMapping("/api/stores/{storeId}/trades/{tradeId}/reject")
    public ResponseEntity<TradeResponse> reject(@PathVariable Long storeId, @PathVariable Long tradeId,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TradeResponse.from(shiftTradeService.reject(storeId, tradeId, userDetails.getUsername())));
    }

    @PostMapping("/api/stores/{storeId}/trades/{tradeId}/cancel")
    public ResponseEntity<TradeResponse> cancel(@PathVariable Long storeId, @PathVariable Long tradeId,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TradeResponse.from(shiftTradeService.cancel(storeId, tradeId, userDetails.getUsername())));
    }

    @Data
    static class TradeRequest {
        @NotNull(message = "넘기기(HANDOVER) 또는 맞교환(EXCHANGE)을 골라주세요.")
        private TradeType type;
        private Long targetEmployeeId;
        private Long targetScheduleId;
        // 매주 이렇게 바꾸기 (그달 남은 같은 요일·시간 근무)
        private Boolean recurring;
    }

    record OptionResponse(Long employeeId, String employeeName, Long scheduleId, LocalDate workDate, LocalTime startTime,
                          LocalTime endTime, boolean available, String blocker) {
        static OptionResponse from(ShiftTradeService.Option o) {
            Schedule s = o.schedule();
            return new OptionResponse(o.employee().getId(), o.employee().getDisplayName(),
                    s != null ? s.getId() : null, s != null ? s.getWorkDate() : null,
                    s != null ? s.getStartTime() : null, s != null ? s.getEndTime() : null, o.available(), o.blocker());
        }
    }

    record OptionsResponse(List<OptionResponse> handover, List<OptionResponse> exchange) {
    }

    record TradeResponse(Long id, TradeType type, TradeStatus status, Long requesterId, String requesterName,
                         LocalDate requesterDate, LocalTime requesterStart, LocalTime requesterEnd, Long targetId,
                         String targetName, LocalDate targetDate, LocalTime targetStart, LocalTime targetEnd,
                         boolean recurring, LocalDateTime createdAt) {
        static TradeResponse from(ShiftTrade t) {
            return new TradeResponse(t.getId(), t.getType(), t.getStatus(), t.getRequester().getId(),
                    t.getRequester().getDisplayName(), t.getRequesterDate(), t.getRequesterStart(), t.getRequesterEnd(),
                    t.getTarget().getId(), t.getTarget().getDisplayName(), t.getTargetDate(), t.getTargetStart(),
                    t.getTargetEnd(), t.isRecurring(), t.getCreatedAt());
        }
    }
}
