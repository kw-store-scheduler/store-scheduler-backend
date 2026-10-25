package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 근무자 프로필 변경 이력 (역량 확정, 혼자 맡길 수 있는 일, 수습 기간, 시급 등). 바뀔 때 당사자에게 알림이 간다
@Entity
@Getter
@NoArgsConstructor
public class EmployeeChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public EmployeeChangeLog(Employee employee, String description) {
        this.employee = employee;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }
}
