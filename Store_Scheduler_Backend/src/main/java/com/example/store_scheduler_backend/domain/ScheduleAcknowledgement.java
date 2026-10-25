package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 확정 근무표를 직원이 확인했는지. 확정할 때 직원마다 만들고, 내 근무표를 열면 확인으로 바뀐다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"proposal_id", "employee_id"}))
public class ScheduleAcknowledgement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id", nullable = false)
    private ScheduleProposal proposal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    private LocalDateTime seenAt;

    private LocalDateTime lastRemindedAt;

    public ScheduleAcknowledgement(ScheduleProposal proposal, Employee employee) {
        this.proposal = proposal;
        this.employee = employee;
    }

    public void see() {
        if (seenAt == null) {
            seenAt = LocalDateTime.now();
        }
    }

    public void remind() {
        lastRemindedAt = LocalDateTime.now();
    }
}
