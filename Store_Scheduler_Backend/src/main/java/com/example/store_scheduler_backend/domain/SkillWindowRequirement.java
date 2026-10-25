package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

// 시간대별로 꼭 있어야 하는 역량 (예: 피크 12-14시 응대 상 1명 필수, 화·금 10-12시 발주 가능자 1명)
// 이 시간대와 겹치는 교대 중 어디에든 있으면 충족. skill과 permission 중 하나를 쓴다
@Entity
@Getter
@NoArgsConstructor
public class SkillWindowRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    private String label;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id")
    private Skill skill;

    // null이면 등급 무관
    @Enumerated(EnumType.STRING)
    private SkillLevel minLevel;

    @Enumerated(EnumType.STRING)
    private EmployeePermission permission;

    @Column(nullable = false)
    private int requiredCount;

    // false면 "우선": 가능하면 맞추지만 다른 조건보다 약하다
    private boolean mandatory;

    // DayOfWeek 이름을 쉼표로 이어 저장. null이면 매일
    private String daysOfWeek;

    public SkillWindowRequirement(Store store, String label, LocalTime startTime, LocalTime endTime, Skill skill,
                                  SkillLevel minLevel, EmployeePermission permission, int requiredCount, boolean mandatory,
                                  List<DayOfWeek> days) {
        this.store = store;
        this.label = label;
        this.startTime = startTime;
        this.endTime = endTime;
        this.skill = skill;
        this.minLevel = minLevel;
        this.permission = permission;
        this.requiredCount = requiredCount;
        this.mandatory = mandatory;
        this.daysOfWeek = days == null || days.isEmpty() ? null
                : String.join(",", days.stream().map(Enum::name).toList());
    }

    public List<DayOfWeek> getDayList() {
        return daysOfWeek == null ? List.of() : Arrays.stream(daysOfWeek.split(",")).map(DayOfWeek::valueOf).toList();
    }

    public boolean appliesOn(DayOfWeek day) {
        return daysOfWeek == null || getDayList().contains(day);
    }
}
