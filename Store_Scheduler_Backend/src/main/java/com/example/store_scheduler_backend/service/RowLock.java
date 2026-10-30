package com.example.store_scheduler_backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 동시에 들어온 수락·승인이 같은 상태를 보고 둘 다 통과하지 않도록, 행을 잠그고 최신 상태로 다시 읽는다
@Component
@RequiredArgsConstructor
public class RowLock {

    private final EntityManager entityManager;

    public void lock(Object entity) {
        entityManager.flush();
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
    }
}
