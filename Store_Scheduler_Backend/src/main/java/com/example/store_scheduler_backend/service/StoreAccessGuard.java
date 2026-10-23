package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 단위 접근 권한 검증을 담당한다.
 * 각 서비스에 중복 구현되어 있던 "매장 조회 + 소유자 확인" 로직을 한곳으로 모은 것.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StoreAccessGuard {

    private final StoreRepository storeRepository;

    /**
     * 매장을 조회한다. 존재하지 않으면 예외를 던진다.
     */
    public Store getStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다."));
    }

    /**
     * 매장을 조회한 뒤 요청자가 해당 매장의 관리자인지 검증하고, 검증된 매장을 반환한다.
     */
    public Store verifyOwner(Long storeId, String username) {
        Store store = getStore(storeId);
        verifyOwner(store, username);
        return store;
    }

    /**
     * 이미 조회한 매장에 대해 요청자가 관리자인지 검증한다.
     */
    public void verifyOwner(Store store, String username) {
        if (!store.getOwner().getUsername().equals(username)) {
            throw new IllegalArgumentException("해당 매장의 관리자만 접근할 수 있습니다.");
        }
    }
}
