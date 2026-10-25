package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.UnavailablePeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface UnavailablePeriodRepository extends JpaRepository<UnavailablePeriod, Long> {
    List<UnavailablePeriod> findByEmployeeIdOrderByStartDateAsc(Long employeeId);
    List<UnavailablePeriod> findByEmployee_StoreIdAndEndDateGreaterThanEqualAndStartDateLessThanEqual(Long storeId, LocalDate from,
                                                                                                      LocalDate to);
}
