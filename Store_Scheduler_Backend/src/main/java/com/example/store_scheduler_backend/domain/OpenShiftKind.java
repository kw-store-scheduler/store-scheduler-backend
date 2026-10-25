package com.example.store_scheduler_backend.domain;

// VACANCY: 결원 요청으로 생긴 빈 근무, EXTRA: 점주가 추가 인원을 모집하는 근무 (단체 예약, 피크 등)
public enum OpenShiftKind {
    VACANCY,
    EXTRA
}
