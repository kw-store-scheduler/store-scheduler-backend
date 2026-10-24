package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.DayOffRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DayOffRequestRepository extends JpaRepository<DayOffRequest, Long> {
    List<DayOffRequest> findByEmployee_StoreIdAndDateBetweenOrderByDateAsc(Long storeId, LocalDate from, LocalDate to);
    List<DayOffRequest> findByEmployeeIdOrderByDateAsc(Long employeeId);
    boolean existsByEmployeeIdAndDate(Long employeeId, LocalDate date);
}
