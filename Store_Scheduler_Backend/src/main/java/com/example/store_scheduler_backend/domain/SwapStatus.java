package com.example.store_scheduler_backend.domain;

// OFFERED: 대타 요청을 보내고 응답을 기다림, PENDING_APPROVAL: 직원끼리 구한 대타를 사장님이 승인하기 전
public enum SwapStatus {
    REQUESTED,
    OFFERED,
    PENDING_APPROVAL,
    ACCEPTED,
    CANCELLED
}
