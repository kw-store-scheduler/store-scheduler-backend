package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.SwapRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SwapRequestRepository extends JpaRepository<SwapRequest, Long> {
    List<SwapRequest> findByStoreIdOrderByCreatedAtDesc(Long storeId);
    List<SwapRequest> findBySchedule_IdIn(Collection<Long> scheduleIds);
}
