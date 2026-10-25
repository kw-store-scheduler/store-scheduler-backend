package com.example.store_scheduler_backend.domain;

// CLOSED: 다른 사람이 먼저 수락했거나 요청이 끝나 더 이상 응답할 수 없음, EXPIRED: 제한 시간 안에 응답 없음
public enum OfferStatus {
    SENT,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    CLOSED
}
