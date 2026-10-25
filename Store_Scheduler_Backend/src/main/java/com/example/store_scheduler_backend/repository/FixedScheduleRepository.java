package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.FixedSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FixedScheduleRepository extends JpaRepository<FixedSchedule, Long> {
    List<FixedSchedule> findByEmployeeId(Long employeeId);
    List<FixedSchedule> findByEmployee_StoreId(Long storeId);
}
