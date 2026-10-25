package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 자동 생성된 근무표 후보안. 관리자가 하나를 확정하기 전까지는 근무표(Schedule)에 반영하지 않는다.
@Entity
@Getter
@NoArgsConstructor
public class ScheduleProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // 생성 기간 시작일. 주간이면 월요일, 월간이면 1일
    @Column(nullable = false)
    private LocalDate weekStart;

    // 기존 데이터 때문에 null 허용, null은 7일(주간)로 본다
    private Integer periodDays;

    @Column(nullable = false)
    private String engineStatus;

    private Long solveMs;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String candidatesJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String shortagesJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposalStatus status = ProposalStatus.PENDING;

    private Integer confirmedIndex;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ScheduleProposal(Store store, LocalDate weekStart, String engineStatus, Long solveMs,
                            String candidatesJson, String shortagesJson) {
        this(store, weekStart, 7, engineStatus, solveMs, candidatesJson, shortagesJson);
    }

    public ScheduleProposal(Store store, LocalDate weekStart, int periodDays, String engineStatus, Long solveMs,
                            String candidatesJson, String shortagesJson) {
        this.store = store;
        this.weekStart = weekStart;
        this.periodDays = periodDays;
        this.engineStatus = engineStatus;
        this.solveMs = solveMs;
        this.candidatesJson = candidatesJson;
        this.shortagesJson = shortagesJson;
        this.status = ProposalStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public LocalDate getPeriodEnd() {
        return weekStart.plusDays((periodDays != null ? periodDays : 7) - 1);
    }

    public void confirm(int candidateIndex) {
        this.status = ProposalStatus.CONFIRMED;
        this.confirmedIndex = candidateIndex;
    }
}
