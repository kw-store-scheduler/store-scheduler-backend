package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShiftSkillRequirementRepository extends JpaRepository<ShiftSkillRequirement, Long> {
    List<ShiftSkillRequirement> findByShift_Store_Id(Long storeId);
    List<ShiftSkillRequirement> findByShiftId(Long shiftId);
    List<ShiftSkillRequirement> findBySkillId(Long skillId);
}
