package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.ClosingReport;
import com.example.store_scheduler_backend.domain.ExceptionCategory;
import com.example.store_scheduler_backend.domain.StaffingException;
import com.example.store_scheduler_backend.service.KoreanHolidays;
import com.example.store_scheduler_backend.service.StaffingPlanService;
import com.example.store_scheduler_backend.service.StaffingSuggestionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "인력 제안 API", description = "마감 보고(객수) 기반 다음 달 교대별 필요 인원 제안")
public class StaffingController {

    private final StaffingSuggestionService staffingSuggestionService;
    private final StaffingPlanService staffingPlanService;

    // 관리자: 예외일 조회
    @GetMapping("/api/stores/{storeId}/staffing-exceptions")
    public ResponseEntity<List<StaffingExceptionResponse>> findExceptions(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(staffingPlanService.findExceptions(storeId, from, to, userDetails.getUsername()).stream()
                .map(StaffingExceptionResponse::from)
                .toList());
    }

    // 관리자: 예외일 등록. endDate를 주면 기간, shiftId를 생략하면 그날 모든 교대
    // 인원은 minStaff·targetStaff(덮어쓰기, 0명이면 휴무) 또는 deltaStaff(평소 대비 +1 등), 교대 시간 변경은 start/endTimeOverride
    @PostMapping("/api/stores/{storeId}/staffing-exceptions")
    public ResponseEntity<StaffingExceptionResponse> addException(
            @PathVariable Long storeId,
            @Valid @RequestBody StaffingExceptionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(StaffingExceptionResponse.from(staffingPlanService.addException(storeId,
                new StaffingPlanService.ExceptionInput(request.getDate(), request.getEndDate(), request.getShiftId(),
                        request.getMinStaff(), request.getTargetStaff(), request.getDeltaStaff(), request.getCategory(),
                        request.getStartTimeOverride(), request.getEndTimeOverride(), request.getNote()),
                userDetails.getUsername())));
    }

    // 공휴일 (예외일 캘린더 표시용)
    @GetMapping("/api/holidays")
    public ResponseEntity<List<HolidayResponse>> getHolidays(@RequestParam int year) {
        return ResponseEntity.ok(KoreanHolidays.of(year).entrySet().stream()
                .map(e -> new HolidayResponse(e.getKey(), e.getValue()))
                .toList());
    }

    // 관리자: 예외일 삭제
    @DeleteMapping("/api/stores/{storeId}/staffing-exceptions/{exceptionId}")
    public ResponseEntity<Void> deleteException(
            @PathVariable Long storeId,
            @PathVariable Long exceptionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        staffingPlanService.deleteException(storeId, exceptionId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // 관리자: 마감 보고 등록 (같은 날짜·교대는 덮어씀)
    @PostMapping("/api/stores/{storeId}/closing-reports")
    public ResponseEntity<ClosingReportResponse> report(
            @PathVariable Long storeId,
            @Valid @RequestBody ClosingReportRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        ClosingReport saved = staffingSuggestionService.report(storeId, request.getShiftId(), request.getDate(),
                request.getCustomerCount(), request.getSales(), userDetails.getUsername());
        return ResponseEntity.ok(ClosingReportResponse.from(saved));
    }

    // 관리자: 기간별 마감 보고 조회
    @GetMapping("/api/stores/{storeId}/closing-reports")
    public ResponseEntity<List<ClosingReportResponse>> findReports(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(staffingSuggestionService.findReports(storeId, from, to, userDetails.getUsername()).stream()
                .map(ClosingReportResponse::from)
                .toList());
    }

    // 관리자: month 직전 4주 마감 보고로 요일·교대별 필요 인원 제안
    @GetMapping("/api/stores/{storeId}/staffing-suggestions")
    public ResponseEntity<StaffingSuggestionService.Result> suggest(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(staffingSuggestionService.suggest(storeId, month, userDetails.getUsername()));
    }

    @Data
    static class ClosingReportRequest {
        @NotNull(message = "근무 시간대를 선택해주세요.")
        private Long shiftId;
        @NotNull(message = "날짜를 입력해주세요.")
        private LocalDate date;
        @NotNull(message = "객수를 입력해주세요.")
        @Min(value = 0, message = "객수는 0 이상이어야 합니다.")
        private Integer customerCount;
        private Long sales;
    }

    @Data
    @AllArgsConstructor
    static class ClosingReportResponse {
        private Long id;
        private Long shiftId;
        private String shiftName;
        private LocalDate date;
        private int customerCount;
        private Long sales;

        static ClosingReportResponse from(ClosingReport r) {
            return new ClosingReportResponse(r.getId(), r.getShift().getId(), r.getShift().getName(), r.getDate(),
                    r.getCustomerCount(), r.getSales());
        }
    }

    @Data
    static class StaffingExceptionRequest {
        @NotNull(message = "날짜를 입력해주세요.")
        private LocalDate date;
        private LocalDate endDate;
        private Long shiftId;
        private Integer minStaff;
        // 생략하면 최소 인원과 같게
        private Integer targetStaff;
        private Integer deltaStaff;
        // DELIVERY(입고)·SALE(세일 행사)·SEASON(시즌)·HOLIDAY_EVE(공휴일 전날)·OTHER
        private ExceptionCategory category;
        private LocalTime startTimeOverride;
        private LocalTime endTimeOverride;
        private String note;
    }

    record StaffingExceptionResponse(Long id, LocalDate date, LocalDate endDate, Long shiftId, String shiftName,
                                     ExceptionCategory category, Integer minStaff, Integer targetStaff, Integer deltaStaff,
                                     LocalTime startTimeOverride, LocalTime endTimeOverride, String note) {
        static StaffingExceptionResponse from(StaffingException e) {
            boolean absolute = e.getDeltaStaff() == null && !e.isTimeOnly();
            return new StaffingExceptionResponse(e.getId(), e.getDate(), e.getEndDate(),
                    e.getShift() != null ? e.getShift().getId() : null,
                    e.getShift() != null ? e.getShift().getName() : null, e.getCategory(),
                    absolute ? e.getMinStaff() : null, absolute ? e.getTargetStaff() : null, e.getDeltaStaff(),
                    e.getStartTimeOverride(), e.getEndTimeOverride(), e.getNote());
        }
    }

    record HolidayResponse(LocalDate date, String name) {
    }
}
