package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleResponseDto;
import com.example.store_scheduler_backend.service.ScheduleAutomationService;
import com.example.store_scheduler_backend.service.SchedulePlanService;
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
import java.time.LocalDateTime;
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
    private final SchedulePlanService schedulePlanService;

    @Operation(summary = "근무표 후보안 생성",
            description = "month(yyyy-MM)를 주면 그달 전체, 아니면 weekStart(월요일)부터 7일(weeks=2면 14일)간의 후보안 3개(공정 우선·인건비 우선·희망 우선)를 생성합니다. "
                    + "둘 다 생략하면 다음 주 월요일 기준입니다. 확정하기 전까지 근무표는 바뀌지 않습니다.")
    @PostMapping("/automate")
    public ResponseEntity<ScheduleProposalResponse> automateSchedule(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false, defaultValue = "1") int weeks,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (month != null) {
            return ResponseEntity.ok(automationService.generateMonthlyProposal(storeId, month, userDetails.getUsername()));
        }
        if (weeks == 2 && weekStart != null) {
            return ResponseEntity.ok(automationService.generateBiweeklyProposal(storeId, weekStart, userDetails.getUsername()));
        }
        LocalDate targetWeek = weekStart != null
                ? weekStart
                : LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return ResponseEntity.ok(automationService.generateProposal(storeId, targetWeek, userDetails.getUsername()));
    }

    @Operation(summary = "다음 근무표 일정", description = "매장 주기(주간·2주·월간)에 따른 다음 기간, 자동 생성 시각, 희망 제출 마감")
    @GetMapping("/plan")
    public ResponseEntity<SchedulePlanService.Plan> getPlan(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(schedulePlanService.getPlan(storeId, LocalDate.now(), userDetails.getUsername()));
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

    // 관리자: 확정 근무표 확인 현황
    @GetMapping("/proposals/{proposalId}/acknowledgements")
    public ResponseEntity<AcknowledgementsResponse> getAcknowledgements(
            @PathVariable Long storeId,
            @PathVariable Long proposalId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<AcknowledgementResponse> list = proposalService.findAcknowledgements(storeId, proposalId, userDetails.getUsername())
                .stream()
                .map(a -> new AcknowledgementResponse(a.getEmployee().getId(), a.getEmployee().getDisplayName(),
                        a.getSeenAt() != null, a.getSeenAt()))
                .toList();
        return ResponseEntity.ok(new AcknowledgementsResponse(list.stream().filter(AcknowledgementResponse::seen).count(),
                list.size(), list));
    }

    // 관리자: 안 본 직원에게 다시 알리기
    @PostMapping("/proposals/{proposalId}/remind")
    public ResponseEntity<RemindResponse> remindUnseen(
            @PathVariable Long storeId,
            @PathVariable Long proposalId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(new RemindResponse(proposalService.remindUnseen(storeId, proposalId, userDetails.getUsername())));
    }

    // 직원: 본인 근무표 조회. 열면 확정 근무표를 확인한 것으로 본다
    @GetMapping("/my")
    public ResponseEntity<List<ScheduleResponseDto>> getMySchedules(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        proposalService.acknowledge(storeId, userDetails.getUsername());
        List<ScheduleResponseDto> result = scheduleService.findMySchedules(storeId, userDetails.getUsername()).stream()
                .map(ScheduleResponseDto::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "근무 직접 추가", description = "휴무·가용 시간·주 52시간 같은 근무 규칙에 걸리면 400과 사유로 응답합니다. force=true면 점주 판단으로 배정합니다.")
    @PostMapping
    public ResponseEntity<ScheduleResponseDto> createSchedule(
            @PathVariable Long storeId,
            @Valid @RequestBody ScheduleCreateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Schedule created = scheduleService.create(storeId, request.getEmployeeId(), request.getWorkDate(), request.getStartTime(),
                request.getEndTime(), Boolean.TRUE.equals(request.getForce()), userDetails.getUsername());
        return ResponseEntity.ok(new ScheduleResponseDto(created));
    }

    // 관리자: 스케줄 단건 수정. employeeId를 주면 담당자도 바꾼다
    @PutMapping("/{scheduleId}")
    public ResponseEntity<ScheduleResponseDto> updateSchedule(
            @PathVariable Long storeId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Schedule updated = scheduleService.update(storeId, scheduleId, request.getEmployeeId(), request.getWorkDate(),
                request.getStartTime(), request.getEndTime(), Boolean.TRUE.equals(request.getForce()), userDetails.getUsername());
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

    record AcknowledgementResponse(Long employeeId, String name, boolean seen, LocalDateTime seenAt) {
    }

    record AcknowledgementsResponse(long seenCount, int total, List<AcknowledgementResponse> employees) {
    }

    record RemindResponse(int remindedCount) {
    }

    @Data
    static class ConfirmRequest {
        @NotNull(message = "확정할 후보안 번호를 입력해주세요.")
        @Min(value = 0, message = "후보안 번호는 0 이상이어야 합니다.")
        private Integer candidateIndex;
    }

    @Data
    static class ScheduleCreateRequest {
        @NotNull(message = "직원을 선택해주세요.")
        private Long employeeId;

        @NotNull(message = "근무 날짜를 입력해주세요.")
        private LocalDate workDate;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;

        private Boolean force;
    }

    @Data
    static class ScheduleUpdateRequest {
        private Long employeeId;
        private Boolean force;

        @NotNull(message = "근무 날짜를 입력해주세요.")
        private LocalDate workDate;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;
    }
}
