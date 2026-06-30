package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Availability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

@Repository
public interface AvailabilityRepository extends JpaRepository<Availability, Long> {
    List<Availability> findByEmployeeId(Long employeeId);
    List<Availability> findByEmployee_StoreId(Long storeId);
    Optional<Availability> findByEmployeeIdAndDayOfWeek(Long employeeId, DayOfWeek dayOfWeek);
}
