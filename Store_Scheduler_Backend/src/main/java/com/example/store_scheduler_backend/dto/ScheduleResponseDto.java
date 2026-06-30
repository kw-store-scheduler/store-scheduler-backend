package com.example.store_scheduler_backend.dto;

import com.example.store_scheduler_backend.domain.Schedule;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
public class ScheduleResponseDto {
    private Long id;
    private Long storeId;
    private Long employeeId;
    private String employeeName;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;

    public ScheduleResponseDto(Schedule schedule) {
        this.id = schedule.getId();
        this.storeId = schedule.getStore().getId();
        this.employeeId = schedule.getEmployee().getId();
        this.employeeName = schedule.getEmployee().getName();
        this.workDate = schedule.getWorkDate();
        this.startTime = schedule.getStartTime();
        this.endTime = schedule.getEndTime();
    }
}
