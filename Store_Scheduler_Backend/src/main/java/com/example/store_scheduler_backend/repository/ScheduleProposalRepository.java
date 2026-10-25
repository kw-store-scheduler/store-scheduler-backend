package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ScheduleProposal;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduleProposalRepository extends JpaRepository<ScheduleProposal, Long> {
    boolean existsByStoreIdAndWeekStart(Long storeId, LocalDate weekStart);
}
