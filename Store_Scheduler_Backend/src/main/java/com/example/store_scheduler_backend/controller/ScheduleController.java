package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.dto.ScheduleGenerationResponse;
import com.example.store_scheduler_backend.dto.ScheduleResponseDto;
import com.example.store_scheduler_backend.service.ScheduleAutomationService;
import com.example.store_scheduler_backend.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    @Operation(summary = "주간 근무표 자동 생성",
            description = "weekStart(월요일)부터 7일간의 근무표를 생성합니다. 생략하면 다음 주 월요일 기준입니다. 같은 주에 기존 근무표가 있으면 교체됩니다.")
    @PostMapping("/automate")
    public ResponseEntity<ScheduleGenerationResponse> automateSchedule(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @AuthenticationPrincipal UserDetails userDetails) {
        LocalDate targetWeek = weekStart != null
                ? weekStart
                : LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return ResponseEntity.ok(automationService.generateWeeklySchedule(storeId, targetWeek, userDetails.getUsername()));
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
    static class ScheduleUpdateRequest {
        @NotNull(message = "근무 날짜를 입력해주세요.")
        private LocalDate workDate;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;
    }
}
