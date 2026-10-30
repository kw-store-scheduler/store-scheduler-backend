package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 출퇴근 기록. 자정을 넘기는 근무도 출근한 날짜(workDate)로 묶는다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "work_date"}))
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private LocalDateTime clockIn;

    private LocalDateTime clockOut;

    public Attendance(Employee employee, LocalDateTime clockIn) {
        this.employee = employee;
        this.store = employee.getStore();
        this.workDate = clockIn.toLocalDate();
        this.clockIn = clockIn;
    }

    public void correct(LocalDateTime clockIn, LocalDateTime clockOut) {
        this.workDate = clockIn.toLocalDate();
        this.clockIn = clockIn;
        this.clockOut = clockOut;
    }

    public void clockOut(LocalDateTime clockOut) {
        this.clockOut = clockOut;
    }
}
