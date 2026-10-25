package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.OpenShift;
import com.example.store_scheduler_backend.domain.OpenShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface OpenShiftRepository extends JpaRepository<OpenShift, Long> {
    List<OpenShift> findByStoreIdAndWorkDateGreaterThanEqualOrderByWorkDateAscStartTimeAsc(Long storeId, LocalDate from);
    List<OpenShift> findBySwapRequestId(Long swapRequestId);
    List<OpenShift> findBySwapRequestIdInAndStatus(Collection<Long> swapRequestIds, OpenShiftStatus status);
}
