package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.StoreTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StoreTaskRepository extends JpaRepository<StoreTask, Long> {
    List<StoreTask> findByStoreIdAndDateOrderByIdAsc(Long storeId, LocalDate date);
    List<StoreTask> findByShiftId(Long shiftId);
}
