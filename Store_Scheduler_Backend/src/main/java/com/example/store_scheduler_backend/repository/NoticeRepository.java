package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Notice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoticeRepository extends JpaRepository<Notice, Long> {
    List<Notice> findByStoreIdOrderByPinnedDescCreatedAtDesc(Long storeId);
}
