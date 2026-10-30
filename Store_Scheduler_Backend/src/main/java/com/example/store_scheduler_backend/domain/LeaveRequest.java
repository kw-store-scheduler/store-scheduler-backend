package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 정규직 연차 신청. 점주가 승인하면 그날은 필수 휴무로 등록된다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "date"}))
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false)
    private LocalDate date;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveStatus status = LeaveStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public LeaveRequest(Employee employee, LocalDate date, String reason) {
        this.employee = employee;
        this.date = date;
        this.reason = reason;
        this.status = LeaveStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void approve() {
        this.status = LeaveStatus.APPROVED;
    }

    public void cancel() {
        this.status = LeaveStatus.CANCELLED;
    }

    // 날짜마다 한 행이라 반려·취소한 날을 다시 신청하면 같은 행을 쓴다
    public void reopen(String reason) {
        this.reason = reason;
        this.status = LeaveStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void reject() {
        this.status = LeaveStatus.REJECTED;
    }
}
