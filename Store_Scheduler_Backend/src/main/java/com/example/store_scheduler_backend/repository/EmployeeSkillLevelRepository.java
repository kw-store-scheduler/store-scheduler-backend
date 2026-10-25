package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.EmployeeSkillLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeSkillLevelRepository extends JpaRepository<EmployeeSkillLevel, Long> {
    List<EmployeeSkillLevel> findByEmployeeId(Long employeeId);
    List<EmployeeSkillLevel> findByEmployee_StoreId(Long storeId);
    List<EmployeeSkillLevel> findBySkillId(Long skillId);
    Optional<EmployeeSkillLevel> findByEmployeeIdAndSkillId(Long employeeId, Long skillId);
}
