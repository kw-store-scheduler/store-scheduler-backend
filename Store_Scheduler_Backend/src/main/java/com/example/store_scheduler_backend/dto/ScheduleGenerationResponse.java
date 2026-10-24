package com.example.store_scheduler_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 근무표 자동 생성 결과.
 * success=false면 근무표를 저장하지 않은 것이다. (status: INFEASIBLE / UNKNOWN 등)
 */
@Getter
@AllArgsConstructor
public class ScheduleGenerationResponse {

    private boolean success;
    private String status;
    private String message;
    private LocalDate weekStart;
    private int savedCount;
    private Long cost;
    private Long solveMs;
    private List<Shortage> shortages;

    public static ScheduleGenerationResponse success(String status, LocalDate weekStart, int savedCount,
                                                     Long cost, Long solveMs, List<Shortage> shortages) {
        String message = shortages.isEmpty()
                ? "근무표를 생성했습니다."
                : "근무표를 생성했지만 최소 인원을 채우지 못한 근무가 " + shortages.size() + "건 있습니다.";
        return new ScheduleGenerationResponse(true, status, message, weekStart, savedCount, cost, solveMs, shortages);
    }

    public static ScheduleGenerationResponse failed(String status, LocalDate weekStart) {
        return new ScheduleGenerationResponse(false, status,
                "현재 조건으로는 근무표를 생성할 수 없습니다. 직원 가용 시간이나 근무 시간대 설정을 확인해주세요.",
                weekStart, 0, null, null, List.of());
    }

    /**
     * 최소 인원 미달 근무
     */
    @Getter
    @AllArgsConstructor
    public static class Shortage {
        private LocalDate date;
        private String shiftName;
        private int required;
        private int actual;
    }
}
