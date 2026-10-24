package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// 결원(대타) 요청. 근무표가 재생성되어 원래 근무가 사라져도 이력이 남도록 날짜·시간을 따로 보관한다
@Entity
@Getter
@NoArgsConstructor
public class SwapRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private Employee requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_id")
    private Employee substitute;

    @Column(nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SwapStatus status = SwapStatus.REQUESTED;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public SwapRequest(Schedule schedule, String reason) {
        this.store = schedule.getStore();
        this.schedule = schedule;
        this.requester = schedule.getEmployee();
        this.workDate = schedule.getWorkDate();
        this.startTime = schedule.getStartTime();
        this.endTime = schedule.getEndTime();
        this.reason = reason;
        this.status = SwapStatus.REQUESTED;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == SwapStatus.REQUESTED || status == SwapStatus.OFFERED;
    }

    public void offer(Employee substitute) {
        this.substitute = substitute;
        this.status = SwapStatus.OFFERED;
    }

    public void accept() {
        this.schedule.setEmployee(substitute);
        this.status = SwapStatus.ACCEPTED;
    }

    public void decline() {
        this.substitute = null;
        this.status = SwapStatus.REQUESTED;
    }

    public void cancel() {
        this.status = SwapStatus.CANCELLED;
    }

    // 근무표 재생성·삭제로 원래 근무가 없어질 때 호출
    public void detachSchedule() {
        if (isActive()) {
            this.status = SwapStatus.CANCELLED;
        }
        this.schedule = null;
    }
}
