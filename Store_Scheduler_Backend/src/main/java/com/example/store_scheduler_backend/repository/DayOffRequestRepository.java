package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DayOffRequestRepository extends JpaRepository<DayOffRequest, Long> {
    List<DayOffRequest> findByEmployee_StoreIdAndDateBetweenOrderByDateAsc(Long storeId, LocalDate from, LocalDate to);
    List<DayOffRequest> findByEmployeeIdOrderByDateAsc(Long employeeId);
    long countByEmployeeIdAndTypeAndDateBetween(Long employeeId, DayOffType type, LocalDate from, LocalDate to);
    Optional<DayOffRequest> findByEmployeeIdAndDate(Long employeeId, LocalDate date);
    boolean existsByEmployeeIdAndDate(Long employeeId, LocalDate date);
}
