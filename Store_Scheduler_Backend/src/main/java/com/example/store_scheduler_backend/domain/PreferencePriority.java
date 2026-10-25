package com.example.store_scheduler_backend.domain;

// 희망끼리 부딪힐 때 무엇을 먼저 지킬지. 위에 둔 항목일수록 엔진 패널티 배율이 커진다
public enum PreferencePriority {
    DAY_OFF,
    SHIFT,
    WEEKEND,
    HOURS,
    RHYTHM
}
