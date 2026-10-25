package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ClosingReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClosingReportRepository extends JpaRepository<ClosingReport, Long> {
    Optional<ClosingReport> findByShiftIdAndDate(Long shiftId, LocalDate date);
    List<ClosingReport> findByShift_Store_IdAndDateBetweenOrderByDateAsc(Long storeId, LocalDate from, LocalDate to);
}
