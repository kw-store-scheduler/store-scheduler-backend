package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final StoreAccessGuard storeAccessGuard;

    @Transactional
    public Store registerStore(String name, String address, String ownerUsername) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("매장 이름은 필수 입력 항목입니다.");
        }
        User owner = userRepository.findByUsername(ownerUsername)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 충돌 방지를 위해 앞 8자리만 사용
        String storeCode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Store store = new Store(name, address, owner, storeCode);
        return storeRepository.save(store);
    }

    public List<Store> findMyStores(String ownerUsername) {
        return storeRepository.findByOwner_Username(ownerUsername);
    }

    public Store findOne(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다."));
    }

    public Store findByStoreCode(String storeCode) {
        return storeRepository.findByStoreCode(storeCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 매장 코드입니다."));
    }

    @Transactional
    public String reissueCode(Long storeId, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        String newCode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        store.reissueCode(newCode);
        return newCode;
    }

    @Transactional
    public Store updateSettings(Long storeId, Boolean guaranteeMustDayOff, Integer customersPerStaff, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        store.updateSettings(guaranteeMustDayOff, customersPerStaff);
        return store;
    }
}