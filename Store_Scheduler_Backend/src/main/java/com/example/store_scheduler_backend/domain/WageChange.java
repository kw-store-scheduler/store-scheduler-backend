package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// 시급 변경 이력. 변경일 이전 근무는 previousWage로 계산해 지난달 급여가 바뀌지 않게 한다
@Entity
@Getter
@NoArgsConstructor
public class WageChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false)
    private int previousWage;

    public WageChange(Employee employee, LocalDate effectiveFrom, int previousWage) {
        this.employee = employee;
        this.effectiveFrom = effectiveFrom;
        this.previousWage = previousWage;
    }
}
