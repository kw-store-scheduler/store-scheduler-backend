package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUser_UsernameOrderByCreatedAtDescIdDesc(String username, Pageable pageable);
    List<Notification> findByUser_UsernameAndReadAtIsNull(String username);
    long countByUser_UsernameAndReadAtIsNull(String username);
}
