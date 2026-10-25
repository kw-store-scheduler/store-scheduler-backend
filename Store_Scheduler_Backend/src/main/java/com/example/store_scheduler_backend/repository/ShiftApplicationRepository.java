package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ShiftApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftApplicationRepository extends JpaRepository<ShiftApplication, Long> {
    List<ShiftApplication> findByOpenShiftIdOrderByAppliedAtAsc(Long openShiftId);
    List<ShiftApplication> findByOpenShiftIdIn(Collection<Long> openShiftIds);
    Optional<ShiftApplication> findByOpenShiftIdAndEmployeeId(Long openShiftId, Long employeeId);
}
