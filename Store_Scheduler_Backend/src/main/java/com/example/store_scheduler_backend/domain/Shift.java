package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalTime;
import java.time.DayOfWeek;

@Entity
@Data // 이 어노테이션이 있어야 getName(), getRequiredStaff() 메서드가 자동으로 생성됩니다.
@NoArgsConstructor
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;            // 시간대 명칭 (예: 오전, 오후, 저녁)
    private LocalTime startTime;    // 출근 시간
    private LocalTime endTime;      // 퇴근 시간
    private Integer requiredStaff;  // 해당 시간대 필요 목표 인원

    @Enumerated(EnumType.STRING) // 요일이 DB에 문자열(MONDAY 등)로 저장되도록 설정
    private DayOfWeek dayOfWeek;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;
}