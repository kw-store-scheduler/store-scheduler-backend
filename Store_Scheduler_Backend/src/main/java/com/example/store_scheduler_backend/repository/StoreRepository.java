package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {
    // JpaRepository<엔티티 타입, PK 타입>을 상속받으면 기본 CRUD 메서드가 자동으로 주입됩니다.
}