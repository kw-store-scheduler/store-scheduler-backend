package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    /**
     * 신규 매장 등록 (비즈니스 로직)
     */
    @Transactional
    public Long registerStore(Store store) {
        // 실무형 예외 처리: 매장 이름이 비어있으면 등록을 막습니다.
        if (store.getName() == null || store.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("매장 이름은 필수 입력 항목입니다.");
        }
        storeRepository.save(store);
        return store.getId();
    }

    /**
     * 전체 매장 조회
     */
    public List<Store> findStores() {
        return storeRepository.findAll();
    }

    /**
     * 단건 매장 조회
     */
    public Store findOne(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다."));
    }
}