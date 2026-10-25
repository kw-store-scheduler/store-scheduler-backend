package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// 빈 근무 게시판의 글. 직원이 지원하면 점주가 골라 승인하고, 승인되면 근무표가 바로 바뀐다
@Entity
@Getter
@NoArgsConstructor
public class OpenShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OpenShiftKind kind;

    // kind가 VACANCY일 때만 있다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "swap_request_id")
    private SwapRequest swapRequest;

    @Column(nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private String title;

    private String note;

    @Column(nullable = false)
    private int headcount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OpenShiftStatus status = OpenShiftStatus.OPEN;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static OpenShift vacancy(SwapRequest request) {
        OpenShift openShift = new OpenShift(request.getStore(), OpenShiftKind.VACANCY, request.getWorkDate(),
                request.getStartTime(), request.getEndTime(), request.getRequester().getDisplayName() + " 결원",
                request.getReason(), 1);
        openShift.swapRequest = request;
        return openShift;
    }

    public static OpenShift extra(Store store, LocalDate workDate, LocalTime startTime, LocalTime endTime, String title,
                                  String note, int headcount) {
        return new OpenShift(store, OpenShiftKind.EXTRA, workDate, startTime, endTime, title, note, headcount);
    }

    private OpenShift(Store store, OpenShiftKind kind, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                      String title, String note, int headcount) {
        this.store = store;
        this.kind = kind;
        this.workDate = workDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.title = title;
        this.note = note;
        this.headcount = headcount;
        this.status = OpenShiftStatus.OPEN;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isOpen() {
        return status == OpenShiftStatus.OPEN;
    }

    public void fill() {
        this.status = OpenShiftStatus.FILLED;
    }

    public void cancel() {
        this.status = OpenShiftStatus.CANCELLED;
    }
}
