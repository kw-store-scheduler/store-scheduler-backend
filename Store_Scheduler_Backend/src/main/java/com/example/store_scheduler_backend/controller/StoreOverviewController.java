package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeePermission;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.SkillLevel;
import com.example.store_scheduler_backend.domain.SkillWindowRequirement;
import com.example.store_scheduler_backend.service.StoreOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@Tag(name = "매장 현황 API", description = "근무자 목록, 역량 매칭, 내 정보 요약")
@RequiredArgsConstructor
public class StoreOverviewController {

    private final StoreOverviewService overviewService;

    @Operation(summary = "근무자 목록", description = "점주용. 직원(직책 순) → 알바. 오늘 상태(WORKING·VACANCY·OFF), 이번 달 근무 시간, 다음 달 희망 제출 여부. date를 생략하면 오늘")
    @GetMapping("/api/stores/{storeId}/roster")
    public ResponseEntity<RosterResponse> getRoster(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetails userDetails) {
        LocalDate target = date != null ? date : LocalDate.now();
        StoreOverviewService.Roster roster = overviewService.getRoster(storeId, target, userDetails.getUsername());
        return ResponseEntity.ok(new RosterResponse(roster.employees().size(), roster.pendingApprovals(),
                roster.employees().stream().map(r -> RosterRow.from(r, target)).toList()));
    }

    @Operation(summary = "역량 매칭", description = "점주용. 그날 시간대별 필요 역량마다 배치된 사람 중 조건을 채운 사람, 충족 여부, 그 시간대 결원")
    @GetMapping("/api/stores/{storeId}/skill-coverage")
    public ResponseEntity<List<CoverageResponse>> getCoverage(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(overviewService.getCoverage(storeId, date != null ? date : LocalDate.now(), userDetails.getUsername())
                .stream().map(CoverageResponse::from).toList());
    }

    @Operation(summary = "내 정보 요약", description = "직원용. 이번 달 근무일·시간, 오늘까지 급여, 누적 시간, 고정 일정 수, 지난달 희망 반영, 안 읽은 알림")
    @GetMapping("/api/stores/{storeId}/me/summary")
    public ResponseEntity<StoreOverviewService.MySummaryView> getMySummary(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        StoreOverviewService.MySummary s = overviewService.getMySummary(storeId, LocalDate.now(), userDetails.getUsername());
        Employee e = s.employee();
        return ResponseEntity.ok(new StoreOverviewService.MySummaryView(e.getId(), e.getName(), e.getEmploymentType(),
                e.getStartDate(), e.getHourlyWage(), e.getStore().getName(), s.monthDays(), s.monthHours(), s.payToDate(),
                s.cumulativeHours(), s.fixedScheduleCount(), s.lastReportRate(), s.lastReportHonored(), s.lastReportAssigned(),
                s.unreadNotifications(), s.pushEnabled()));
    }

    record RosterRow(Long employeeId, String name, EmploymentType employmentType, String position, boolean newcomer,
                     String todayStatus, LocalTime todayStart, LocalTime todayEnd, double monthHours, boolean nextMonthSubmitted) {
        static RosterRow from(StoreOverviewService.EmployeeRow r, LocalDate today) {
            Employee e = r.employee();
            Schedule s = r.todaySchedule();
            return new RosterRow(e.getId(), e.getDisplayName(), e.getEmploymentType(),
                    e.getPosition() != null ? e.getPosition().getLabel() : null, e.isNewcomer(today), r.todayStatus(),
                    s != null ? s.getStartTime() : null, s != null ? s.getEndTime() : null, r.monthHours(), r.nextMonthSubmitted());
        }
    }

    record RosterResponse(int total, long pendingApprovals, List<RosterRow> employees) {
    }

    record VacancyBrief(Long requestId, String requesterName, LocalTime startTime, LocalTime endTime, String status) {
    }

    record CoverageResponse(Long requirementId, String label, LocalTime startTime, LocalTime endTime, String skillName,
                            SkillLevel minLevel, EmployeePermission permission, boolean mandatory, int requiredCount,
                            List<String> matchedNames, boolean satisfied, List<VacancyBrief> vacancies) {
        static CoverageResponse from(StoreOverviewService.Coverage c) {
            SkillWindowRequirement w = c.requirement();
            return new CoverageResponse(w.getId(), w.getLabel(), w.getStartTime(), w.getEndTime(),
                    w.getSkill() != null ? w.getSkill().getName() : null, w.getMinLevel(), w.getPermission(), w.isMandatory(),
                    w.getRequiredCount(), c.matched().stream().map(Employee::getDisplayName).toList(), c.satisfied(),
                    c.vacancies().stream()
                            .map(v -> new VacancyBrief(v.getId(), v.getRequester().getDisplayName(), v.getStartTime(),
                                    v.getEndTime(), v.getStatus().name()))
                            .toList());
        }
    }
}
