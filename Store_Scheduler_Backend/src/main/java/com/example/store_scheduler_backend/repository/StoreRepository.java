package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {
    Optional<Store> findByStoreCode(String storeCode);
    List<Store> findByOwner_Username(String username);
    @EntityGraph(attributePaths = "owner")
    List<Store> findByAutoGenerateTrue();
}
