package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.FixedSchedule;
import com.example.store_scheduler_backend.domain.PreferenceLevel;
import com.example.store_scheduler_backend.domain.PreferencePriority;
import com.example.store_scheduler_backend.domain.PreferenceSubmission;
import com.example.store_scheduler_backend.service.PreferenceReportService;
import com.example.store_scheduler_backend.service.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@Tag(name = "희망 제출 API", description = "다음 달 날짜×교대 희망, 고정 일정, 개인 근무 규칙과 우선순위")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;
    private final PreferenceReportService preferenceReportService;

    @Operation(summary = "희망 반영 리포트", description = "확정된 month(yyyy-MM) 근무표 기준. 선호 반영률, 휴무 수용률, 최근 4주 부담 근무(주말·마감·야간)의 팀 평균 비교, 반영하지 못한 근무와 배정 사유")
    @GetMapping("/api/stores/{storeId}/preference-report/my")
    public ResponseEntity<ReportResponse> getMyReport(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal UserDetails userDetails) {
        PreferenceReportService.Report r = preferenceReportService.getMine(storeId, month, userDetails.getUsername());
        return ResponseEntity.ok(new ReportResponse(r.month().toString(), r.assignedCount(), r.honoredCount(),
                r.preferenceRate(), r.dayOffRequested(), r.dayOffHonored(), r.dayOffRate(), r.mustRequested(),
                r.preferRequested(), r.burdenFrom(), r.burdenTo(),
                r.burdens().stream().map(b -> new BurdenResponse(b.kind(), b.mine(), b.teamAverage())).toList(),
                r.unfulfilled().stream()
                        .map(u -> new UnfulfilledResponse(u.schedule().getId(), u.schedule().getWorkDate(),
                                u.assignedShiftName(), u.schedule().getStartTime(), u.schedule().getEndTime(),
                                u.preferredShiftNames(), u.reasons()))
                        .toList()));
    }

    // 직원: 매주 반복되는 고정 일정 조회
    @GetMapping("/api/stores/{storeId}/fixed-schedules/my")
    public ResponseEntity<List<FixedScheduleResponse>> getMyFixedSchedules(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(preferenceService.findMyFixedSchedules(storeId, userDetails.getUsername()).stream()
                .map(FixedScheduleResponse::from).toList());
    }

    // 직원: 고정 일정 통째로 교체 (빈 목록이면 모두 삭제)
    @PutMapping("/api/stores/{storeId}/fixed-schedules/my")
    public ResponseEntity<List<FixedScheduleResponse>> replaceMyFixedSchedules(
            @PathVariable Long storeId,
            @RequestBody List<FixedScheduleRequest> request,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<PreferenceService.FixedScheduleInput> inputs = request.stream()
                .map(r -> new PreferenceService.FixedScheduleInput(r.getDayOfWeek(), r.getStartTime(), r.getEndTime(), r.getLabel()))
                .toList();
        return ResponseEntity.ok(preferenceService.replaceMyFixedSchedules(storeId, inputs, userDetails.getUsername()).stream()
                .map(FixedScheduleResponse::from).toList());
    }

    @Operation(summary = "내 희망 조회", description = "month(yyyy-MM)의 모든 근무 칸과 내가 고른 가능/선호, 고정 일정으로 잠긴 칸, 제출한 개인 규칙")
    @GetMapping("/api/stores/{storeId}/preferences/my")
    public ResponseEntity<MonthResponse> getMine(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(MonthResponse.from(preferenceService.getMine(storeId, month, userDetails.getUsername())));
    }

    @Operation(summary = "희망 제출", description = "slots에 가능/선호인 칸만 보냅니다. 보내지 않은 칸은 불가입니다. 다시 제출하면 그달 칸을 통째로 바꿉니다.")
    @PutMapping("/api/stores/{storeId}/preferences/my")
    public ResponseEntity<MonthResponse> submit(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestBody SubmissionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<PreferenceService.SlotInput> slots = request.getSlots() == null ? null : request.getSlots().stream()
                .map(s -> new PreferenceService.SlotInput(s.getDate(), s.getShiftId(), s.getLevel()))
                .toList();
        PreferenceService.SubmissionInput input = new PreferenceService.SubmissionInput(slots, request.getMaxWeekendShifts(),
                request.getMinMonthlyHours(), request.getMaxMonthlyHours(), request.getMaxConsecutiveDays(),
                Boolean.TRUE.equals(request.getNoOpenAfterClose()), request.getPriorities());
        return ResponseEntity.ok(MonthResponse.from(preferenceService.submit(storeId, month, input, userDetails.getUsername())));
    }

    @Operation(summary = "희망 제출 현황", description = "점주용. 직원별 제출 여부와 가능/선호 칸 수, 마감일")
    @GetMapping("/api/stores/{storeId}/preferences")
    public ResponseEntity<StoreStatusResponse> getStoreStatus(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal UserDetails userDetails) {
        PreferenceService.StoreView view = preferenceService.getStoreStatus(storeId, month, userDetails.getUsername());
        return ResponseEntity.ok(new StoreStatusResponse(view.month().toString(), view.deadline(),
                ChronoUnit.DAYS.between(LocalDate.now(), view.deadline()), view.submittedCount(), view.employees().size(),
                view.employees().stream()
                        .map(e -> new EmployeeStatus(e.employee().getId(), e.employee().getDisplayName(),
                                e.submission() != null,
                                e.submission() != null ? e.submission().getSubmittedAt() : null,
                                e.submission() != null && e.submission().getSubmittedAt().toLocalDate().isAfter(view.deadline()),
                                e.availableCount(), e.preferredCount(), e.weekendAvailable()))
                        .toList()));
    }

    // kind: WEEKEND(주말), CLOSE(마감), NIGHT(야간)
    record BurdenResponse(String kind, int mine, double teamAverage) {
    }

    record UnfulfilledResponse(Long scheduleId, LocalDate workDate, String assignedShiftName, LocalTime startTime,
                               LocalTime endTime, List<String> preferredShiftNames, List<String> reasons) {
    }

    // preferenceRate는 그달 희망을 내지 않았으면 null, dayOffRate는 휴무 신청이 없으면 null
    record ReportResponse(String month, int assignedCount, int honoredCount, Double preferenceRate, int dayOffRequested,
                          int dayOffHonored, Double dayOffRate, int mustRequested, int preferRequested,
                          LocalDate burdenFrom, LocalDate burdenTo, List<BurdenResponse> burdens,
                          List<UnfulfilledResponse> unfulfilled) {
    }

    @Data
    static class FixedScheduleRequest {
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
        private String label;
    }

    record FixedScheduleResponse(Long id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String label) {
        static FixedScheduleResponse from(FixedSchedule f) {
            return new FixedScheduleResponse(f.getId(), f.getDayOfWeek(), f.getStartTime(), f.getEndTime(), f.getLabel());
        }
    }

    @Data
    static class SlotRequest {
        private LocalDate date;
        private Long shiftId;
        private PreferenceLevel level;
    }

    @Data
    static class SubmissionRequest {
        private List<SlotRequest> slots;
        // 아래 규칙은 생략하면 쓰지 않는다
        private Integer maxWeekendShifts;
        private Integer minMonthlyHours;
        private Integer maxMonthlyHours;
        private Integer maxConsecutiveDays;
        private Boolean noOpenAfterClose;
        // 지키고 싶은 순서대로. 빠진 항목은 기본 비중
        private List<PreferencePriority> priorities;
    }

    record ShiftSummary(Long id, String name, LocalTime startTime, LocalTime endTime) {
    }

    record CellResponse(LocalDate date, Long shiftId, PreferenceLevel level, boolean locked, String lockLabel) {
    }

    record SubmissionResponse(LocalDateTime submittedAt, Integer maxWeekendShifts, Integer minMonthlyHours,
                              Integer maxMonthlyHours, Integer maxConsecutiveDays, boolean noOpenAfterClose,
                              List<PreferencePriority> priorities) {
        static SubmissionResponse from(PreferenceSubmission s) {
            return s == null ? null : new SubmissionResponse(s.getSubmittedAt(), s.getMaxWeekendShifts(), s.getMinMonthlyHours(),
                    s.getMaxMonthlyHours(), s.getMaxConsecutiveDays(), s.isNoOpenAfterClose(), s.getPriorityList());
        }
    }

    record MonthResponse(String month, LocalDate deadline, boolean submitted, List<ShiftSummary> shifts,
                         List<CellResponse> cells, int availableCount, int preferredCount, SubmissionResponse submission) {
        static MonthResponse from(PreferenceService.MonthView v) {
            List<CellResponse> cells = v.cells().stream()
                    .map(c -> new CellResponse(c.date(), c.shift().getId(), c.level(), c.locked(), c.lockLabel()))
                    .toList();
            return new MonthResponse(v.month().toString(), v.deadline(), v.submission() != null,
                    v.shifts().stream().map(s -> new ShiftSummary(s.getId(), s.getName(), s.getStartTime(), s.getEndTime())).toList(),
                    cells,
                    (int) cells.stream().filter(c -> c.level() == PreferenceLevel.AVAILABLE).count(),
                    (int) cells.stream().filter(c -> c.level() == PreferenceLevel.PREFERRED).count(),
                    SubmissionResponse.from(v.submission()));
        }
    }

    record EmployeeStatus(Long employeeId, String name, boolean submitted, LocalDateTime submittedAt, boolean late,
                          int availableCount, int preferredCount, boolean weekendAvailable) {
    }

    record StoreStatusResponse(String month, LocalDate deadline, long daysUntilDeadline, long submittedCount,
                               int employeeCount, List<EmployeeStatus> employees) {
    }
}
