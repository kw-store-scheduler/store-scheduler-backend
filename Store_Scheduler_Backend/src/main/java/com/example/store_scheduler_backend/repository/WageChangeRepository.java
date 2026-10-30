package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.WageChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WageChangeRepository extends JpaRepository<WageChange, Long> {
    List<WageChange> findByEmployeeIdOrderByIdAsc(Long employeeId);
}
