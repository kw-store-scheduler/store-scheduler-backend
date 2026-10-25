package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ShiftPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ShiftPreferenceRepository extends JpaRepository<ShiftPreference, Long> {
    List<ShiftPreference> findByEmployeeIdAndDateBetween(Long employeeId, LocalDate from, LocalDate to);
    List<ShiftPreference> findByEmployee_StoreIdAndDateBetween(Long storeId, LocalDate from, LocalDate to);
    List<ShiftPreference> findByShiftId(Long shiftId);
}
