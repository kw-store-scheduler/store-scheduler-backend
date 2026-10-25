package com.example.store_scheduler_backend.domain;

// 혼자 맡길 수 있는 일. 매니저만 정한다
public enum EmployeePermission {
    SOLO_OPEN("혼자 오픈"),
    CLOSING_SETTLEMENT("마감 정산"),
    STORE_KEY("매장 열쇠"),
    ORDERING("발주 시스템");

    private final String label;

    EmployeePermission(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
