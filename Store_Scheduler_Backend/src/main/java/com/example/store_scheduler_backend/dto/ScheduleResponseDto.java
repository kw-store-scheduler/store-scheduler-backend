package com.example.store_scheduler_backend.dto;

import com.example.store_scheduler_backend.domain.Schedule;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
public class ScheduleResponseDto {
    private Long id;
    private String employeeName;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;

    // Entity를 받아서 필요한 알맹이만 DTO로 옮겨 담는 생성자
    public ScheduleResponseDto(Schedule schedule) {
        this.id = schedule.getId();
        this.employeeName = schedule.getEmployee().getName(); // 직원 이름만 쏙 빼옵니다.
        this.workDate = schedule.getWorkDate();
        this.startTime = schedule.getStartTime();
        this.endTime = schedule.getEndTime();
    }
}