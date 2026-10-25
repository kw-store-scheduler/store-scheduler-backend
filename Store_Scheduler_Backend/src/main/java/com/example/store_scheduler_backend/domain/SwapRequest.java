package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// 결원(대타) 요청. 근무표가 재생성되어 원래 근무가 사라져도 이력이 남도록 날짜·시간을 따로 보관한다
// 근무 일부만 빠지면(partialFrom) startTime이 빠지는 시각이 되고, 대타가 정해질 때 근무를 둘로 나눈다
@Entity
@Getter
@NoArgsConstructor
public class SwapRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private Employee requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_id")
    private Employee substitute;

    @Column(nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    // 사장님만 본다. 동료에게는 사유 분류 없이 "사정이 생겼어요"로만 보인다
    private String reason;

    // 기존 데이터 때문에 null 허용
    @Enumerated(EnumType.STRING)
    private AbsenceReason reasonCategory;

    private LocalTime partialFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SwapStatus status = SwapStatus.REQUESTED;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    public SwapRequest(Schedule schedule, String reason) {
        this(schedule, reason, null, null);
    }

    public SwapRequest(Schedule schedule, String reason, AbsenceReason reasonCategory, LocalTime partialFrom) {
        this.store = schedule.getStore();
        this.schedule = schedule;
        this.requester = schedule.getEmployee();
        this.workDate = schedule.getWorkDate();
        this.startTime = partialFrom != null ? partialFrom : schedule.getStartTime();
        this.endTime = schedule.getEndTime();
        this.reason = reason;
        this.reasonCategory = reasonCategory;
        this.partialFrom = partialFrom;
        this.status = SwapStatus.REQUESTED;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == SwapStatus.REQUESTED || status == SwapStatus.OFFERED || status == SwapStatus.PENDING_APPROVAL;
    }

    public void markOffered() {
        this.status = SwapStatus.OFFERED;
    }

    // 직원끼리 구한 대타. 사장님이 승인하면 assign
    public void awaitApproval(Employee substitute) {
        this.substitute = substitute;
        this.status = SwapStatus.PENDING_APPROVAL;
    }

    // 다시 대타를 찾는 상태 (사장님이 승인하지 않았거나 요청이 모두 끝남)
    public void reopen() {
        this.substitute = null;
        this.status = SwapStatus.REQUESTED;
    }

    // 대타 확정. 근무 전체면 담당자를 바꾸고, 일부면 요청자 근무를 줄인 뒤 대타 근무를 새로 돌려준다
    public Schedule assign(Employee substitute) {
        this.substitute = substitute;
        this.status = SwapStatus.ACCEPTED;
        this.resolvedAt = LocalDateTime.now();
        if (partialFrom == null) {
            this.schedule.setEmployee(substitute);
            return null;
        }
        Schedule cover = new Schedule();
        cover.setStore(store);
        cover.setEmployee(substitute);
        cover.setWorkDate(workDate);
        cover.setStartTime(partialFrom);
        cover.setEndTime(endTime);
        cover.setAssignmentReasons("결원 대타 (" + requester.getDisplayName() + "님 근무 일부)");
        this.schedule.setEndTime(partialFrom);
        return cover;
    }

    public void cancel() {
        this.status = SwapStatus.CANCELLED;
        this.resolvedAt = LocalDateTime.now();
    }

    // 근무표 재생성·삭제로 원래 근무가 없어질 때 호출
    public void detachSchedule() {
        if (isActive()) {
            cancel();
        }
        this.schedule = null;
    }
}
