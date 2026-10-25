package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 직원 역량 등급. 직원이 신청(requestedLevel)하면 매니저가 확정(level)해야 배치에 쓰인다
// 이 행이 없고 직원 보유 스킬에만 있으면 등급 미지정(기존 데이터)으로, 등급 조건 없는 요구에만 쓰인다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "skill_id"}))
public class EmployeeSkillLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Enumerated(EnumType.STRING)
    private SkillLevel level;

    @Enumerated(EnumType.STRING)
    private SkillLevel requestedLevel;

    private LocalDateTime requestedAt;

    public EmployeeSkillLevel(Employee employee, Skill skill) {
        this.employee = employee;
        this.skill = skill;
    }

    public boolean isPending() {
        return requestedLevel != null;
    }

    public void request(SkillLevel level) {
        this.requestedLevel = level;
        this.requestedAt = LocalDateTime.now();
    }

    public void confirm(SkillLevel level) {
        this.level = level;
        this.requestedLevel = null;
        this.requestedAt = null;
    }

    public void keep() {
        this.requestedLevel = null;
        this.requestedAt = null;
    }
}
