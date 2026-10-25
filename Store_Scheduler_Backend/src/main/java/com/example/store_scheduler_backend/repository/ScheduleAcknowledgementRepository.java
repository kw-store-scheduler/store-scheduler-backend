package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ScheduleAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleAcknowledgementRepository extends JpaRepository<ScheduleAcknowledgement, Long> {
    List<ScheduleAcknowledgement> findByProposalIdOrderByIdAsc(Long proposalId);
    List<ScheduleAcknowledgement> findByEmployeeIdAndSeenAtIsNull(Long employeeId);
}
