package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 교대마다 특정 스킬 보유자가 몇 명 필요한지 (예: 오픈에 일본어 가능자 1명)
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"shift_id", "skill_id"}))
public class ShiftSkillRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Column(nullable = false)
    private int requiredCount;

    public ShiftSkillRequirement(Shift shift, Skill skill, int requiredCount) {
        this.shift = shift;
        this.skill = skill;
        this.requiredCount = requiredCount;
    }
}
