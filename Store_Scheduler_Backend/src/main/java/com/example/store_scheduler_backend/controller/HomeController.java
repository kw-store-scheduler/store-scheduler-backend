package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Attendance;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.SwapOffer;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.service.HomeService;
import com.example.store_scheduler_backend.service.PayrollService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@Tag(name = "홈 API", description = "점주 홈·알바 홈 화면 요약")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @Operation(summary = "점주 홈", description = "이번 주 예상 인건비, 직원 수, 승인 대기, 진행 중인 결원, 오늘 교대별 인원, 오늘 출근 기록. date를 생략하면 오늘입니다.")
    @GetMapping("/api/stores/{storeId}/home/owner")
    public ResponseEntity<OwnerHomeResponse> getOwnerHome(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetails userDetails) {
        HomeService.OwnerHome home = homeService.getOwnerHome(storeId, date != null ? date : LocalDate.now(),
                userDetails.getUsername());
        return ResponseEntity.ok(new OwnerHomeResponse(
                home.store().getId(),
                home.store().getName(),
                home.date(),
                home.weekStart(),
                home.weeklyLaborCost(),
                home.employeeCount(),
                home.pendingApprovals(),
                home.activeVacancies().stream().map(VacancyResponse::from).toList(),
                home.todayShifts().stream()
                        .map(s -> new ShiftStatusResponse(s.shiftName(), s.startTime(), s.endTime(), s.requiredStaff(),
                                s.employeeNames(), s.shortage()))
                        .toList(),
                home.todayAttendance().stream().map(AttendanceSummary::from).toList(),
                PreferenceStatusResponse.from(home.nextMonthPreferences()),
                home.pendingLeaves(),
                home.pendingTrades(),
                home.pendingContractApprovals()));
    }

    @Operation(summary = "알바 홈", description = "오늘 근무와 같이 일하는 사람, 이번 주 근무, 나에게 온 대타 요청, 이번 달 예상 급여. date를 생략하면 오늘입니다.")
    @GetMapping("/api/stores/{storeId}/home/worker")
    public ResponseEntity<WorkerHomeResponse> getWorkerHome(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetails userDetails) {
        HomeService.WorkerHome home = homeService.getWorkerHome(storeId, date != null ? date : LocalDate.now(),
                userDetails.getUsername());
        int wage = home.employee().getHourlyWage() != null ? home.employee().getHourlyWage() : 0;
        PayrollService.EmployeePayroll pay = home.monthlyPay();
        return ResponseEntity.ok(new WorkerHomeResponse(
                home.store().getId(),
                home.store().getName(),
                home.employee().getId(),
                home.employee().getDisplayName(),
                home.employee().getEmploymentType(),
                home.date(),
                DayScheduleResponse.from(home.today()),
                home.coworkers(),
                home.todayAttendance() != null ? AttendanceSummary.from(home.todayAttendance()) : null,
                home.week().stream().map(DayScheduleResponse::from).toList(),
                home.swapOffers().stream().map(o -> SwapOfferResponse.from(o, wage)).toList(),
                new MonthlyPayResponse(pay.getTotalHours(), pay.getRegularWage(), pay.getWeeklyHolidayPay(), pay.getNightPay(),
                        pay.getOvertimePay(), pay.getHolidayPay(), pay.getTotalPay(), pay.getDeduction(), pay.getNetPay()),
                PreferenceStatusResponse.from(home.nextMonthPreferences()),
                home.leaveBalance() != null ? new LeaveBalanceResponse(home.leaveBalance().entitled(),
                        home.leaveBalance().used(), home.leaveBalance().remaining()) : null,
                home.latestNotice() != null ? new NoticeSummary(home.latestNotice().getId(), home.latestNotice().getTitle(),
                        home.latestNotice().getCreatedAt()) : null));
    }

    record VacancyResponse(Long requestId, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                           String requesterName, String reason, String status, String substituteName) {
        static VacancyResponse from(SwapRequest r) {
            return new VacancyResponse(r.getId(), r.getWorkDate(), r.getStartTime(), r.getEndTime(),
                    r.getRequester().getDisplayName(), r.getReason(), r.getStatus().name(),
                    r.getSubstitute() != null ? r.getSubstitute().getDisplayName() : null);
        }
    }

    record ShiftStatusResponse(String shiftName, LocalTime startTime, LocalTime endTime, int requiredStaff,
                               List<String> employeeNames, int shortage) {
    }

    record AttendanceSummary(Long employeeId, String employeeName, LocalDateTime clockIn, LocalDateTime clockOut) {
        static AttendanceSummary from(Attendance a) {
            return new AttendanceSummary(a.getEmployee().getId(), a.getEmployee().getDisplayName(), a.getClockIn(),
                    a.getClockOut());
        }
    }

    record OwnerHomeResponse(Long storeId, String storeName, LocalDate date, LocalDate weekStart, long weeklyLaborCost,
                             long employeeCount, long pendingApprovals, List<VacancyResponse> activeVacancies,
                             List<ShiftStatusResponse> todayShifts, List<AttendanceSummary> todayAttendance,
                             PreferenceStatusResponse nextMonthPreferences, long pendingLeaves,
                             long pendingTrades, long pendingContractApprovals) {
    }

    // 정규직만 값이 있다
    record LeaveBalanceResponse(int entitled, int used, int remaining) {
    }

    record NoticeSummary(Long id, String title, LocalDateTime createdAt) {
    }

    // daysUntilDeadline이 음수면 마감이 지났다는 뜻
    record PreferenceStatusResponse(String month, LocalDate deadline, long daysUntilDeadline, long submittedCount,
                                    long employeeCount, boolean submittedByMe) {
        static PreferenceStatusResponse from(HomeService.PreferenceStatus s) {
            return new PreferenceStatusResponse(s.month().toString(), s.deadline(),
                    ChronoUnit.DAYS.between(LocalDate.now(), s.deadline()), s.submittedCount(), s.employeeCount(),
                    s.submittedByMe());
        }
    }

    // 근무가 없는 날은 scheduleId와 시간이 null
    record DayScheduleResponse(LocalDate date, Long scheduleId, String shiftName, LocalTime startTime, LocalTime endTime) {
        static DayScheduleResponse from(HomeService.DaySchedule d) {
            Schedule s = d.schedule();
            return new DayScheduleResponse(d.date(), s != null ? s.getId() : null, d.shiftName(),
                    s != null ? s.getStartTime() : null, s != null ? s.getEndTime() : null);
        }
    }

    record SwapOfferResponse(Long offerId, Long requestId, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                             String requesterName, long expectedPay) {
        static SwapOfferResponse from(SwapOffer o, int wage) {
            SwapRequest r = o.getSwapRequest();
            long minutes = Duration.between(r.getStartTime(), r.getEndTime()).toMinutes();
            if (minutes <= 0) {
                minutes += 24 * 60;
            }
            return new SwapOfferResponse(o.getId(), r.getId(), r.getWorkDate(), r.getStartTime(), r.getEndTime(),
                    r.getRequester().getDisplayName(), Math.round(minutes / 60.0 * wage));
        }
    }

    record MonthlyPayResponse(double totalHours, long regularWage, long weeklyHolidayPay, long nightPay, long overtimePay, long holidayPay,
                              long totalPay, long deduction, long netPay) {
    }

    record WorkerHomeResponse(Long storeId, String storeName, Long employeeId, String employeeName,
                              EmploymentType employmentType, LocalDate date, DayScheduleResponse today,
                              List<String> coworkers, AttendanceSummary todayAttendance, List<DayScheduleResponse> week,
                              List<SwapOfferResponse> swapOffers, MonthlyPayResponse monthlyPay,
                              PreferenceStatusResponse nextMonthPreferences, LeaveBalanceResponse leaveBalance,
                              NoticeSummary latestNotice) {
    }
}
