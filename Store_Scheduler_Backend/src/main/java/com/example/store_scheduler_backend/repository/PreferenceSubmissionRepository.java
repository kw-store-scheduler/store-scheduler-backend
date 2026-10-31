package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.PreferenceSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PreferenceSubmissionRepository extends JpaRepository<PreferenceSubmission, Long> {
    Optional<PreferenceSubmission> findByEmployeeIdAndMonth(Long employeeId, LocalDate month);
    List<PreferenceSubmission> findByEmployee_StoreIdAndMonth(Long storeId, LocalDate month);
    long countByEmployee_StoreIdAndEmployee_StatusAndMonthGreaterThanEqualAndContractExceedApprovedFalseAndContractExceedDecidedAtIsNull(
            Long storeId, EmployeeStatus status, LocalDate month);
}
