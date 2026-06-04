package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor

public class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // 직원 이름

    private String phoneNumber; // 전화번호

    @Column(nullable = false)
    private Integer hourlyWage; // 시급 (노동법 기반 급여 정산용)

    // 지연 로딩(LAZY) 설정, 외래 키(store_id) 매핑 for 성능 최적화
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store; // 직원의 소속 매장
}
