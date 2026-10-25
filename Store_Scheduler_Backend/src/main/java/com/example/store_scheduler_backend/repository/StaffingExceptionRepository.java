package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.StaffingException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StaffingExceptionRepository extends JpaRepository<StaffingException, Long> {
    List<StaffingException> findByStoreIdAndDateBetweenOrderByDateAsc(Long storeId, LocalDate from, LocalDate to);
    List<StaffingException> findByShiftId(Long shiftId);
    List<StaffingException> findByStoreIdAndDateLessThanEqualOrderByDateAsc(Long storeId, LocalDate to);
}
