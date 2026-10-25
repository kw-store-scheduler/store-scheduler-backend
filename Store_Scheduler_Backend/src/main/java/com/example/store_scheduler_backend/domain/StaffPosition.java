package com.example.store_scheduler_backend.domain;

// 정규직 직책. 알바에게는 붙이지 않고, 입사 1개월 이내면 화면에서 "신입"을 붙인다
public enum StaffPosition {
    MANAGER("매니저"),
    SENIOR("시니어"),
    JUNIOR("주니어");

    private final String label;

    StaffPosition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
