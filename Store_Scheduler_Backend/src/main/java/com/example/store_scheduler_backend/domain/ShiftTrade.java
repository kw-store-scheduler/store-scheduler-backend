package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// 근무 넘기기·맞교환. 동료가 수락하고 사장님이 승인하면 근무표 담당자가 바뀐다. 근무 시각은 이력용으로 따로 남긴다
@Entity
@Getter
@NoArgsConstructor
public class ShiftTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private Employee requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_schedule_id")
    private Schedule requesterSchedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", nullable = false)
    private Employee target;

    // 맞교환일 때만 있다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_schedule_id")
    private Schedule targetSchedule;

    @Column(nullable = false)
    private LocalDate requesterDate;

    @Column(nullable = false)
    private LocalTime requesterStart;

    @Column(nullable = false)
    private LocalTime requesterEnd;

    private LocalDate targetDate;

    private LocalTime targetStart;

    private LocalTime targetEnd;

    // 같은 요일·시간 근무를 그달 남은 주에도 똑같이 바꾼다
    private boolean recurring;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeStatus status = TradeStatus.REQUESTED;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    public ShiftTrade(TradeType type, Schedule requesterSchedule, Employee target, Schedule targetSchedule, boolean recurring) {
        this.store = requesterSchedule.getStore();
        this.type = type;
        this.requester = requesterSchedule.getEmployee();
        this.requesterSchedule = requesterSchedule;
        this.target = target;
        this.targetSchedule = targetSchedule;
        this.requesterDate = requesterSchedule.getWorkDate();
        this.requesterStart = requesterSchedule.getStartTime();
        this.requesterEnd = requesterSchedule.getEndTime();
        if (targetSchedule != null) {
            this.targetDate = targetSchedule.getWorkDate();
            this.targetStart = targetSchedule.getStartTime();
            this.targetEnd = targetSchedule.getEndTime();
        }
        this.recurring = recurring;
        this.status = TradeStatus.REQUESTED;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isOpen() {
        return status == TradeStatus.REQUESTED || status == TradeStatus.PEER_ACCEPTED;
    }

    public void peerAccept() {
        this.status = TradeStatus.PEER_ACCEPTED;
    }

    // 근무표 재생성·삭제로 근무가 없어질 때
    public void detachSchedules() {
        if (isOpen()) {
            resolve(TradeStatus.CANCELLED);
        }
        this.requesterSchedule = null;
        this.targetSchedule = null;
    }

    public void resolve(TradeStatus status) {
        this.status = status;
        this.resolvedAt = LocalDateTime.now();
    }
}
