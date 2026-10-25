package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.SwapStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface SwapRequestRepository extends JpaRepository<SwapRequest, Long> {
    List<SwapRequest> findByStoreIdOrderByCreatedAtDesc(Long storeId);
    List<SwapRequest> findBySchedule_IdIn(Collection<Long> scheduleIds);
    List<SwapRequest> findBySubstituteIdAndStatusAndWorkDateBetween(Long substituteId, SwapStatus status, LocalDate from,
                                                                   LocalDate to);
    List<SwapRequest> findByStatusInAndWorkDateBefore(Collection<SwapStatus> statuses, LocalDate date);
}
