package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ShiftDayStaffing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShiftDayStaffingRepository extends JpaRepository<ShiftDayStaffing, Long> {
    List<ShiftDayStaffing> findByShiftId(Long shiftId);
    List<ShiftDayStaffing> findByShift_Store_Id(Long storeId);
}
