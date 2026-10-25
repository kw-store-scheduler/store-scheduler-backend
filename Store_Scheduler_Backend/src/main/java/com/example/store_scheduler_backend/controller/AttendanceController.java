package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Attendance;
import com.example.store_scheduler_backend.service.AttendanceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@Tag(name = "출퇴근 API", description = "출근·퇴근 기록과 조회")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    // 직원: 출근
    @PostMapping("/api/stores/{storeId}/attendance/clock-in")
    public ResponseEntity<AttendanceResponse> clockIn(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AttendanceResponse.from(attendanceService.clockIn(storeId, userDetails.getUsername())));
    }

    // 직원: 퇴근
    @PostMapping("/api/stores/{storeId}/attendance/clock-out")
    public ResponseEntity<AttendanceResponse> clockOut(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AttendanceResponse.from(attendanceService.clockOut(storeId, userDetails.getUsername())));
    }

    // 관리자: 날짜별 출퇴근 현황 (생략하면 오늘)
    @GetMapping("/api/stores/{storeId}/attendance")
    public ResponseEntity<List<AttendanceResponse>> getByDate(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetails userDetails) {
        LocalDate target = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(attendanceService.findByDate(storeId, target, userDetails.getUsername()).stream()
                .map(AttendanceResponse::from)
                .toList());
    }

    // 직원: 본인 출퇴근 기록
    @GetMapping("/api/stores/{storeId}/attendance/my")
    public ResponseEntity<List<AttendanceResponse>> getMine(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(attendanceService.findMine(storeId, from, to, userDetails.getUsername()).stream()
                .map(AttendanceResponse::from)
                .toList());
    }

    record AttendanceResponse(Long id, Long employeeId, String employeeName, LocalDate workDate,
                              LocalDateTime clockIn, LocalDateTime clockOut,
                              LocalTime scheduledStart, LocalTime scheduledEnd, boolean late) {
        static AttendanceResponse from(AttendanceService.AttendanceView view) {
            Attendance a = view.attendance();
            return new AttendanceResponse(a.getId(), a.getEmployee().getId(), a.getEmployee().getDisplayName(),
                    a.getWorkDate(), a.getClockIn(), a.getClockOut(), view.scheduledStart(), view.scheduledEnd(),
                    view.late());
        }
    }
}
