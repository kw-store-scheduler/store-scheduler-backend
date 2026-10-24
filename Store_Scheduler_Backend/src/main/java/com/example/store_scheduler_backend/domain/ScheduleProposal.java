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

    @Column(nullable = false)
    private LocalDate weekStart;

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
        this.store = store;
        this.weekStart = weekStart;
        this.engineStatus = engineStatus;
        this.solveMs = solveMs;
        this.candidatesJson = candidatesJson;
        this.shortagesJson = shortagesJson;
        this.status = ProposalStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void confirm(int candidateIndex) {
        this.status = ProposalStatus.CONFIRMED;
        this.confirmedIndex = candidateIndex;
    }
}
