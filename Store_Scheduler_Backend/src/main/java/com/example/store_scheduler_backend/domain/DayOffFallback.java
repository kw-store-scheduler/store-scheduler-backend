package com.example.store_scheduler_backend.domain;

// 희망 휴무를 지켜주지 못할 때의 대안. AFTERNOON_OFF·ANNUAL_LEAVE·HALF_DAY는 엔진에 반영하고 나머지는 점주에게 보여준다
// ANNUAL_LEAVE(연차)·HALF_DAY(반차)는 정규직용
public enum DayOffFallback {
    AFTERNOON_OFF,
    ANNUAL_LEAVE,
    HALF_DAY,
    SWAP_DAY,
    FIND_SUBSTITUTE,
    WORK_ANYWAY
}
