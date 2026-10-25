package com.example.store_scheduler_backend.domain;

// 결원 사유 분류. PERSONAL은 대타를 못 구하면 약속 점수가 깎인다
public enum AbsenceReason {
    SICK,
    FAMILY_EVENT,
    PERSONAL,
    OTHER
}
