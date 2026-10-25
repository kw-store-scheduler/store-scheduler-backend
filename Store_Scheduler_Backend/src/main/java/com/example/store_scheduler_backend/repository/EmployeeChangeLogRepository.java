package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.EmployeeChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeChangeLogRepository extends JpaRepository<EmployeeChangeLog, Long> {
    List<EmployeeChangeLog> findByEmployeeIdOrderByCreatedAtDescIdDesc(Long employeeId);
}
