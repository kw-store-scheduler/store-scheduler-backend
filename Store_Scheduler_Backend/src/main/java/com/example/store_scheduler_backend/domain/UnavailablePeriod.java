package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;

// "이번 달만 예외": 시험 기간처럼 정해진 기간 동안 근무할 수 없는 날
@Entity
@Getter
@NoArgsConstructor
public class UnavailablePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    // true면 그 기간의 평일만 불가
    private boolean weekdaysOnly;

    private String note;

    public UnavailablePeriod(Employee employee, LocalDate startDate, LocalDate endDate, boolean weekdaysOnly, String note) {
        this.employee = employee;
        this.startDate = startDate;
        this.endDate = endDate;
        this.weekdaysOnly = weekdaysOnly;
        this.note = note;
    }

    public boolean covers(LocalDate date) {
        if (date.isBefore(startDate) || date.isAfter(endDate)) {
            return false;
        }
        return !weekdaysOnly || (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY);
    }
}
