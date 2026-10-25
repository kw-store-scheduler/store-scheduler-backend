package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 날짜별 업무 체크리스트 (오픈·마감 점검 등). 담당자를 정하지 않으면 그날 그 교대 근무자 모두에게 보인다
@Entity
@Getter
@NoArgsConstructor
public class StoreTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String title;

    // null이면 그날 근무자 전체
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private Employee assignee;

    private boolean done;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "done_by_id")
    private Employee doneBy;

    private LocalDateTime doneAt;

    public StoreTask(Store store, LocalDate date, String title, Shift shift, Employee assignee) {
        this.store = store;
        this.date = date;
        this.title = title;
        this.shift = shift;
        this.assignee = assignee;
    }

    public void check(Employee employee, boolean done) {
        this.done = done;
        this.doneBy = done ? employee : null;
        this.doneAt = done ? LocalDateTime.now() : null;
    }
}
