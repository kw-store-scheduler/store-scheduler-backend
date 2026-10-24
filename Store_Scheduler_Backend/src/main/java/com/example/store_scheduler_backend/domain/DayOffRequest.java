package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 날짜별 휴무 신청. MUST는 엔진에서 사실상 하드 제약, PREFER는 가능하면 지켜준다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "date"}))
public class DayOffRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayOffType type;

    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public DayOffRequest(Employee employee, LocalDate date, DayOffType type, String reason) {
        this.employee = employee;
        this.date = date;
        this.type = type;
        this.reason = reason;
        this.createdAt = LocalDateTime.now();
    }
}
