package com.example.store_scheduler_backend.domain;

// 희망 휴무를 지켜주지 못할 때의 대안. AFTERNOON_OFF는 엔진에 반영하고 나머지는 점주에게 보여준다
public enum DayOffFallback {
    AFTERNOON_OFF,
    SWAP_DAY,
    FIND_SUBSTITUTE,
    WORK_ANYWAY
}
