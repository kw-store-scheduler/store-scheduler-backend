package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<Skill, Long> {
    List<Skill> findByStoreId(Long storeId);
    Optional<Skill> findByStoreIdAndName(Long storeId, String name);
    boolean existsByStoreIdAndName(Long storeId, String name);
}