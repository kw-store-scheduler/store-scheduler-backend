package com.example.store_scheduler_backend.domain;

// 몇 명 필요한지

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Getter
@NoArgsConstructor
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어느 매장의 근무 타임라인인지 연결 (N:1)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayOfWeek dayOfWeek; // 요일 (MONDAY, TUESDAY 등)

    @Column(nullable = false)
    private LocalTime startTime; // 근무 시작 시간

    @Column(nullable = false)
    private LocalTime endTime; // 근무 종료 시간

    @Column(nullable = false)
    private Integer requiredEmployeeCount; // 해당 타임에 필요한 알바생 수
}