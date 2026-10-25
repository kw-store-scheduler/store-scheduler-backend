package com.example.store_scheduler_backend.domain;

import java.time.LocalTime;
import java.util.List;

// 매장 등록 시 업종에 맞는 스킬과 기본 교대를 미리 만들어 준다. 등록 후에는 점주가 자유롭게 고친다
public enum Industry {
    CAFE("카페",
            List.of("음료 제조", "응대 속도", "오픈·마감", "외국어", "정산"),
            List.of(new ShiftPreset("오픈", 9, 14), new ShiftPreset("미들", 12, 18), new ShiftPreset("마감", 17, 22))),
    CLOTHING("의류",
            List.of("고객 응대", "진열·VMD", "재고 관리", "오픈·마감", "정산"),
            List.of(new ShiftPreset("오픈", 10, 16), new ShiftPreset("마감", 16, 22))),
    CONVENIENCE_STORE("편의점",
            List.of("계산·POS", "물품 진열", "발주·검수", "오픈·마감", "정산"),
            List.of(new ShiftPreset("오전", 6, 14), new ShiftPreset("오후", 14, 22), new ShiftPreset("야간", 22, 6))),
    PERFUME("향수",
            List.of("향 상담", "고객 응대", "포장", "재고 관리", "정산"),
            List.of(new ShiftPreset("오픈", 10, 16), new ShiftPreset("마감", 16, 22))),
    RESTAURANT("음식점",
            List.of("조리 보조", "서빙", "주방 정리", "오픈·마감", "정산"),
            List.of(new ShiftPreset("점심", 10, 15), new ShiftPreset("저녁", 16, 22))),
    ETC("기타",
            List.of("고객 응대", "오픈·마감", "정산"),
            List.of());

    public record ShiftPreset(String name, LocalTime startTime, LocalTime endTime) {
        ShiftPreset(String name, int startHour, int endHour) {
            this(name, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
        }
    }

    private final String label;
    private final List<String> skills;
    private final List<ShiftPreset> shifts;

    Industry(String label, List<String> skills, List<ShiftPreset> shifts) {
        this.label = label;
        this.skills = skills;
        this.shifts = shifts;
    }

    public String getLabel() {
        return label;
    }

    public List<String> getSkills() {
        return skills;
    }

    public List<ShiftPreset> getShifts() {
        return shifts;
    }
}
