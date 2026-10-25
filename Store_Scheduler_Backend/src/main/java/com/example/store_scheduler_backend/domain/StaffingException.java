package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

// 특정 날짜·기간만 인원이나 교대 시간을 바꾸는 예외일 (입고일, 행사, 시즌, 공휴일 전날, 휴점 등)
// shift가 null이면 그날 모든 교대에 적용. deltaStaff가 있으면 평소 인원에 더하고, 없으면 min/targetStaff로 덮어쓴다(0명이면 휴무)
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

    // 기간 예외의 마지막 날. null이면 하루
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private ExceptionCategory category;

    private Integer deltaStaff;

    // 그날 교대 시간 변경 (예: 마감 23시까지, 21시 조기 종료). 근무표 확정 때 근무 시각에 반영
    private LocalTime startTimeOverride;

    private LocalTime endTimeOverride;

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

    public StaffingException(Store store, LocalDate date, LocalDate endDate, Shift shift, Integer minStaff, Integer targetStaff,
                             Integer deltaStaff, ExceptionCategory category, LocalTime startTimeOverride,
                             LocalTime endTimeOverride, String note) {
        this(store, date, shift, minStaff != null ? minStaff : 0, targetStaff != null ? targetStaff : 0, note);
        this.endDate = endDate;
        this.deltaStaff = deltaStaff;
        this.category = category;
        this.startTimeOverride = startTimeOverride;
        this.endTimeOverride = endTimeOverride;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(date) && !day.isAfter(endDate != null ? endDate : date);
    }

    public LocalDate getLastDate() {
        return endDate != null ? endDate : date;
    }

    // 인원은 바꾸지 않고 시간만 바꾸는 예외
    public boolean isTimeOnly() {
        return deltaStaff == null && minStaff == 0 && targetStaff == 0
                && (startTimeOverride != null || endTimeOverride != null);
    }

    public boolean appliesTo(Shift target) {
        return shift == null || shift.getId().equals(target.getId());
    }
}
