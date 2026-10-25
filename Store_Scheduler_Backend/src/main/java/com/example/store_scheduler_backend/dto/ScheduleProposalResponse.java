package com.example.store_scheduler_backend.dto;

import com.example.store_scheduler_backend.domain.ProposalStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 근무표 자동 생성 결과(후보안 목록).
 * success=false면 엔진이 해를 찾지 못해 후보안이 없다는 뜻이다. (status: INFEASIBLE / UNKNOWN 등)
 */
@Getter
@AllArgsConstructor
public class ScheduleProposalResponse {

    private Long proposalId;
    private boolean success;
    private String status;
    private String message;
    private LocalDate weekStart;
    private Long solveMs;
    private ProposalStatus proposalStatus;
    private Integer confirmedIndex;
    private List<Shortage> shortages;
    private List<Candidate> candidates;
    private Notice notice;

    public static ScheduleProposalResponse failed(String status, LocalDate weekStart) {
        return new ScheduleProposalResponse(null, false, status,
                "현재 조건으로는 근무표를 생성할 수 없습니다. 직원 가용 시간이나 근무 시간대 설정을 확인해주세요.",
                weekStart, null, null, null, List.of(), List.of(), null);
    }

    // 관리자가 확정 전에 꼭 봐야 할 건수. 균형 해(0번) 기준
    public record Notice(int shortageCount, int mustDayOffViolationCount, int preferDayOffViolationCount, String summary) {
        public boolean needsAttention() {
            return shortageCount + mustDayOffViolationCount + preferDayOffViolationCount > 0;
        }
    }

    public static Notice noticeFor(List<Candidate> candidates, List<Shortage> shortages) {
        List<DayOffViolation> violations = candidates.isEmpty() ? List.of() : candidates.get(0).dayOffViolations();
        int must = (int) violations.stream().filter(v -> "MUST".equals(v.type())).count();
        int prefer = violations.size() - must;
        List<String> parts = new java.util.ArrayList<>();
        if (!shortages.isEmpty()) {
            parts.add("인원 부족 " + shortages.size() + "건");
        }
        if (must > 0) {
            parts.add("필수 휴무 위반 " + must + "건(예비 대타 확인 필요)");
        }
        if (prefer > 0) {
            parts.add("희망 휴무 위반 " + prefer + "건");
        }
        String summary = parts.isEmpty() ? "확인이 필요한 항목이 없습니다." : String.join(", ", parts);
        return new Notice(shortages.size(), must, prefer, summary);
    }

    public static String messageFor(int candidateCount, int shortageCount) {
        String message = "근무표 후보안 " + candidateCount + "개를 생성했습니다.";
        if (shortageCount > 0) {
            message += " 최소 인원을 채우지 못한 근무가 " + shortageCount + "건 있습니다.";
        }
        return message;
    }

    // label: 어떤 기준으로 만든 안인지 (균형 / 공평성 우선 / 인건비 우선, 결과가 같으면 합쳐서 표시)
    // changedFromCurrent: 생성 시점의 확정 근무표와 비교해 달라진 칸 수 (추가 + 삭제)
    public record Candidate(int index, String label, String description, long estimatedCost, int changedFromCurrent,
                            List<EmployeeSummary> employees, List<Assignment> assignments,
                            List<DayOffViolation> dayOffViolations) {
    }

    // 휴무를 신청한 날에 배정된 근무. 필수 휴무면 그 근무를 대신할 수 있는 예비 대타(backups)를 함께 준다
    public record DayOffViolation(Long employeeId, String employeeName, LocalDate date, String type,
                                  String shiftName, LocalTime startTime, LocalTime endTime, List<Backup> backups) {
    }

    // 가능한 사람이 없으면 eligible=false인 직원이 사유와 함께 온다
    public record Backup(Long employeeId, String name, boolean eligible, List<String> reasons) {
    }

    public record EmployeeSummary(Long employeeId, String name, int workDays, int hours) {
    }

    // reasons: 이 직원이 이 근무에 배정된 근거 (기존 근무 유지, 필요 스킬, 가용 시간, 이번 주 근무 시간 등)
    public record Assignment(Long employeeId, String employeeName, LocalDate workDate,
                             String shiftName, LocalTime startTime, LocalTime endTime, List<String> reasons) {
    }

    // skillName이 있으면 해당 스킬 보유자 부족, 없으면 인원 자체 부족
    public record Shortage(LocalDate date, String shiftName, int required, int actual, String skillName) {
    }
}
