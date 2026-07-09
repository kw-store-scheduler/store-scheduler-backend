package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByStoreIdOrderByWorkDateAscStartTimeAsc(Long storeId);
    List<Schedule> findByStoreIdAndEmployeeIdOrderByWorkDateAscStartTimeAsc(Long storeId, Long employeeId);
    List<Schedule> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(Long employeeId, LocalDate start, LocalDate end);
}
