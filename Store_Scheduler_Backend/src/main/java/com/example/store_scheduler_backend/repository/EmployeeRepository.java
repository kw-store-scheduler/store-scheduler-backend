package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByName(String name);
    List<Employee> findByStoreId(Long storeId);
    List<Employee> findByStoreIdAndStatus(Long storeId, EmployeeStatus status);
    Optional<Employee> findByStoreIdAndUserId(Long storeId, Long userId);
    List<Employee> findByUserId(Long userId);
    Optional<Employee> findByStoreIdAndName(Long storeId, String name);
}
