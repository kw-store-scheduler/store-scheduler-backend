package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 결원 하나에 여러 동료에게 보낸 대타 요청 한 건. 먼저 수락한 한 명으로 정해진다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"swap_request_id", "candidate_id"}))
public class SwapOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "swap_request_id", nullable = false)
    private SwapRequest swapRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Employee candidate;

    // 사장님이 보낸 요청은 수락하면 바로 확정, 직원이 보낸 요청은 사장님 승인을 거친다
    private boolean sentByOwner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferStatus status = OfferStatus.SENT;

    @Column(nullable = false)
    private LocalDateTime sentAt;

    private LocalDateTime respondedAt;

    public SwapOffer(SwapRequest swapRequest, Employee candidate, boolean sentByOwner) {
        this.swapRequest = swapRequest;
        this.candidate = candidate;
        this.sentByOwner = sentByOwner;
        this.status = OfferStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }

    // 거절·만료된 사람에게 다시 보내는 경우
    public void resend(boolean sentByOwner) {
        this.sentByOwner = sentByOwner;
        this.status = OfferStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.respondedAt = null;
    }

    public boolean isPending() {
        return status == OfferStatus.SENT;
    }

    public void respond(OfferStatus status) {
        this.status = status;
        this.respondedAt = LocalDateTime.now();
    }
}
