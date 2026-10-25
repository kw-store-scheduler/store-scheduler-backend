package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// 특정 날짜만 인원을 바꾸는 예외일 (단체 예약, 휴점 등). shift가 null이면 그날 모든 교대에 적용, 0명이면 휴무
@Entity
@Getter
@NoArgsConstructor
public class StaffingException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(nullable = false)
    private int minStaff;

    @Column(nullable = false)
    private int targetStaff;

    private String note;

    public StaffingException(Store store, LocalDate date, Shift shift, int minStaff, int targetStaff, String note) {
        this.store = store;
        this.date = date;
        this.shift = shift;
        this.minStaff = minStaff;
        this.targetStaff = targetStaff;
        this.note = note;
    }

    public boolean appliesTo(Shift target) {
        return shift == null || shift.getId().equals(target.getId());
    }
}
