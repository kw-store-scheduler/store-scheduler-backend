package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.SkillWindowRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SkillWindowRequirementRepository extends JpaRepository<SkillWindowRequirement, Long> {
    List<SkillWindowRequirement> findByStoreIdOrderByStartTimeAsc(Long storeId);
    List<SkillWindowRequirement> findBySkillId(Long skillId);
}
