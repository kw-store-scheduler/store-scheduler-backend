package com.example.store_scheduler_backend.domain;

// 동료 수락(PEER_ACCEPTED) → 사장님 승인(APPROVED) 순서
public enum TradeStatus {
    REQUESTED,
    PEER_ACCEPTED,
    APPROVED,
    DECLINED,
    REJECTED,
    CANCELLED
}
