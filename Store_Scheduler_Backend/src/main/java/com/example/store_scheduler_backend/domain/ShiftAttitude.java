package com.example.store_scheduler_backend.domain;

// 정규직 교대 선호. 가능 시간을 따로 묻지 않고 교대마다 선호/상관없음/피하고 싶음만 받는다
public enum ShiftAttitude {
    PREFER,
    NEUTRAL,
    AVOID
}
