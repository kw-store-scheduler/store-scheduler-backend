package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// 날짜×교대별 근무 희망. 해당 기간에 제출한 직원은 요일별 가용 시간 대신 이 값으로 근무표를 만든다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "date", "shift_id"}))
public class ShiftPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PreferenceLevel level;

    public ShiftPreference(Employee employee, LocalDate date, Shift shift, PreferenceLevel level) {
        this.employee = employee;
        this.date = date;
        this.shift = shift;
        this.level = level;
    }
}
