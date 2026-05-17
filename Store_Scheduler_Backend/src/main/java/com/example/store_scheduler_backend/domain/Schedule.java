package com.example.store_scheduler_backend.domain;

// 확정 스케줄 entity

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Getter
@NoArgsConstructor
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 누구의 확정 스케줄인지 연결 (N:1)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    // 어느 매장의 스케줄인지 연결 (N:1) - 빠른 조회를 위한 매장 정보 직접 연결
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(nullable = false)
    private LocalDate workDate; // 실제 근무 날짜 (예: 2026-05-18)

    @Column(nullable = false)
    private LocalTime startTime; // 확정된 출근 시간

    @Column(nullable = false)
    private LocalTime endTime; // 확정된 퇴근 시간
}