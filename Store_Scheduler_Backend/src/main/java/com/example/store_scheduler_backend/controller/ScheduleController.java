package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleResponseDto;
import com.example.store_scheduler_backend.service.ScheduleAutomationService;
import com.example.store_scheduler_backend.service.ScheduleProposalService;
import com.example.store_scheduler_backend.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stores/{storeId}/schedules")
@RequiredArgsConstructor
@Tag(name = "스케줄 API", description = "스케줄 자동 생성 및 조회")
public class ScheduleController {

    private final ScheduleAutomationService automationService;
    private final ScheduleService scheduleService;
    private final ScheduleProposalService proposalService;

    @Operation(summary = "근무표 후보안 생성",
            description = "month(yyyy-MM)를 주면 그달 전체, 아니면 weekStart(월요일)부터 7일간의 후보안 3개(공정 우선·인건비 우선·희망 우선)를 생성합니다. "
                    + "둘 다 생략하면 다음 주 월요일 기준입니다. 확정하기 전까지 근무표는 바뀌지 않습니다.")
    @PostMapping("/automate")
    public ResponseEntity<ScheduleProposalResponse> automateSchedule(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (month != null) {
            return ResponseEntity.ok(automationService.generateMonthlyProposal(storeId, month, userDetails.getUsername()));
        }
        LocalDate targetWeek = weekStart != null
                ? weekStart
                : LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return ResponseEntity.ok(automationService.generateProposal(storeId, targetWeek, userDetails.getUsername()));
    }

    // 관리자: 근무표 후보안 조회
    @GetMapping("/proposals/{proposalId}")
    public ResponseEntity<ScheduleProposalResponse> getProposal(
            @PathVariable Long storeId,
            @PathVariable Long proposalId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(proposalService.getProposal(storeId, proposalId, userDetails.getUsername()));
    }

    // 관리자: 후보안 중 하나로 근무표 확정
    @Operation(summary = "근무표 후보안 확정", description = "선택한 후보안으로 해당 주 근무표를 교체하고 직원들에게 알림을 보냅니다.")
    @PostMapping("/proposals/{proposalId}/confirm")
    public ResponseEntity<ScheduleProposalResponse> confirmProposal(
            @PathVariable Long storeId,
            @PathVariable Long proposalId,
            @Valid @RequestBody ConfirmRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(proposalService.confirm(storeId, proposalId, request.getCandidateIndex(), userDetails.getUsername()));
    }

    // 관리자/직원: 매장 전체 근무표 조회
    @GetMapping
    public ResponseEntity<List<ScheduleResponseDto>> getSchedules(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<ScheduleResponseDto> result = scheduleService.findByStore(storeId, userDetails.getUsername()).stream()
                .map(ScheduleResponseDto::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 직원: 본인 근무표 조회
    @GetMapping("/my")
    public ResponseEntity<List<ScheduleResponseDto>> getMySchedules(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<ScheduleResponseDto> result = scheduleService.findMySchedules(storeId, userDetails.getUsername()).stream()
                .map(ScheduleResponseDto::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 스케줄 단건 수정
    @PutMapping("/{scheduleId}")
    public ResponseEntity<ScheduleResponseDto> updateSchedule(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Schedule updated = scheduleService.update(storeId, scheduleId, request.getWorkDate(), request.getStartTime(), request.getEndTime(), userDetails.getUsername());
        return ResponseEntity.ok(new ScheduleResponseDto(updated));
    }

    // 관리자: 스케줄 단건 삭제
    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal UserDetails userDetails) {
        scheduleService.delete(storeId, scheduleId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Data
    static class ConfirmRequest {
        @NotNull(message = "확정할 후보안 번호를 입력해주세요.")
        @Min(value = 0, message = "후보안 번호는 0 이상이어야 합니다.")
        private Integer candidateIndex;
    }

    @Data
    static class ScheduleUpdateRequest {
        @NotNull(message = "근무 날짜를 입력해주세요.")
        private LocalDate workDate;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;
    }
}
