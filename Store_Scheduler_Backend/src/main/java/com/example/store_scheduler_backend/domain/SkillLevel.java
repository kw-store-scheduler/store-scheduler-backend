package com.example.store_scheduler_backend.domain;

// 역량 등급. NONE은 "없음"으로 신청할 때만 쓴다
public enum SkillLevel {
    NONE,
    LOW,
    MID,
    HIGH;

    public boolean atLeast(SkillLevel other) {
        return compareTo(other) >= 0;
    }
}
