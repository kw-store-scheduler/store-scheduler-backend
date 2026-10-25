package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.LeaveRequest;
import com.example.store_scheduler_backend.domain.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    List<LeaveRequest> findByEmployeeIdOrderByDateDesc(Long employeeId);
    List<LeaveRequest> findByEmployee_StoreIdAndStatusOrderByDateAsc(Long storeId, LeaveStatus status);
    long countByEmployee_StoreIdAndStatus(Long storeId, LeaveStatus status);
    long countByEmployeeIdAndStatusInAndDateGreaterThanEqual(Long employeeId, List<LeaveStatus> statuses, LocalDate from);
    boolean existsByEmployeeIdAndDateAndStatusIn(Long employeeId, LocalDate date, List<LeaveStatus> statuses);
}
