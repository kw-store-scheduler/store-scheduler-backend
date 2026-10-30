package com.example.store_scheduler_backend.dto;

import com.example.store_scheduler_backend.domain.Schedule;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Getter
public class ScheduleResponseDto {
    private Long id;
    private Long storeId;
    private Long employeeId;
    private String employeeName;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    // 자동 생성 근무표를 확정할 때 저장한 배정 사유. 직접 추가·수정한 근무는 빈 목록
    private List<String> reasons;

    // 다른 직원의 배정 사유에는 가용 시간·휴무 신청 같은 개인 사정이 들어 있어 빼고 보여준다
    public static ScheduleResponseDto withoutReasons(Schedule schedule) {
        ScheduleResponseDto dto = new ScheduleResponseDto(schedule);
        dto.reasons = List.of();
        return dto;
    }

    public ScheduleResponseDto(Schedule schedule) {
        this.id = schedule.getId();
        this.storeId = schedule.getStore().getId();
        this.employeeId = schedule.getEmployee().getId();
        this.employeeName = schedule.getEmployee().getName();
        this.workDate = schedule.getWorkDate();
        this.startTime = schedule.getStartTime();
        this.endTime = schedule.getEndTime();
        this.reasons = schedule.getAssignmentReasons() == null ? List.of()
                : List.of(schedule.getAssignmentReasons().split("\n"));
    }
}
