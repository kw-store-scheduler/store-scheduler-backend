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

    public static ScheduleProposalResponse failed(String status, LocalDate weekStart) {
        return new ScheduleProposalResponse(null, false, status,
                "현재 조건으로는 근무표를 생성할 수 없습니다. 직원 가용 시간이나 근무 시간대 설정을 확인해주세요.",
                weekStart, null, null, null, List.of(), List.of());
    }

    public static String messageFor(int candidateCount, int shortageCount) {
        String message = "근무표 후보안 " + candidateCount + "개를 생성했습니다.";
        if (shortageCount > 0) {
            message += " 최소 인원을 채우지 못한 근무가 " + shortageCount + "건 있습니다.";
        }
        return message;
    }

    // changedFromCurrent: 생성 시점의 확정 근무표와 비교해 달라진 칸 수 (추가 + 삭제)
    public record Candidate(int index, long estimatedCost, int changedFromCurrent,
                            List<EmployeeSummary> employees, List<Assignment> assignments) {
    }

    public record EmployeeSummary(Long employeeId, String name, int workDays, int hours) {
    }

    public record Assignment(Long employeeId, String employeeName, LocalDate workDate,
                             String shiftName, LocalTime startTime, LocalTime endTime) {
    }

    // skillName이 있으면 해당 스킬 보유자 부족, 없으면 인원 자체 부족
    public record Shortage(LocalDate date, String shiftName, int required, int actual, String skillName) {
    }
}
