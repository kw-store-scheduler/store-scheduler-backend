package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    Optional<Attendance> findByEmployeeIdAndWorkDate(Long employeeId, LocalDate workDate);
    Optional<Attendance> findFirstByEmployeeIdAndClockOutIsNullOrderByClockInDesc(Long employeeId);
    List<Attendance> findByStoreIdAndWorkDateOrderByClockInAsc(Long storeId, LocalDate workDate);
    List<Attendance> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(Long employeeId, LocalDate from, LocalDate to);
}
