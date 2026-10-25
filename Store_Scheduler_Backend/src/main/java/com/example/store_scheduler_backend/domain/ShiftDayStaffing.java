package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;

// 교대의 요일별 인원. 없는 요일은 교대의 requiredStaff를 목표, 1명을 최소로 쓴다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"shift_id", "day_of_week"}))
public class ShiftDayStaffing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private int minStaff;

    @Column(nullable = false)
    private int targetStaff;

    public ShiftDayStaffing(Shift shift, DayOfWeek dayOfWeek, int minStaff, int targetStaff) {
        this.shift = shift;
        this.dayOfWeek = dayOfWeek;
        this.minStaff = minStaff;
        this.targetStaff = targetStaff;
    }
}
