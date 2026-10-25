package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;

// 매주 반복되는 개인 고정 일정(수업, 다른 알바 등). 겹치는 교대는 희망 제출 화면에서 잠기고 근무 불가로 본다
@Entity
@Getter
@NoArgsConstructor
public class FixedSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private String label;

    public FixedSchedule(Employee employee, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String label) {
        this.employee = employee;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.label = label;
    }

    // 자정을 넘기는 교대는 그날 자정까지만 비교한다
    public boolean overlaps(LocalTime shiftStart, LocalTime shiftEnd) {
        LocalTime end = shiftEnd.isAfter(shiftStart) ? shiftEnd : LocalTime.MAX;
        return startTime.isBefore(end) && shiftStart.isBefore(endTime);
    }
}
