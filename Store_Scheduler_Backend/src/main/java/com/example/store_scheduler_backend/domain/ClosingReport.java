package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 마감 보고: 날짜·교대별 실제 객수. 다음 달 인력 제안의 근거 데이터
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"shift_id", "date"}))
public class ClosingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private int customerCount;

    private Long sales;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public ClosingReport(Shift shift, LocalDate date, int customerCount, Long sales) {
        this.shift = shift;
        this.date = date;
        update(customerCount, sales);
    }

    public void update(int customerCount, Long sales) {
        this.customerCount = customerCount;
        this.sales = sales;
        this.updatedAt = LocalDateTime.now();
    }
}
