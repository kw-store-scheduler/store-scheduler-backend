package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 근로기준법 가산수당 적용 여부. 상시 5인 미만이면 연장·야간 가산이 빠지고 주휴수당만 계산한다
@Component
@RequiredArgsConstructor
public class LaborPolicy {

    public static final int FIVE_PLUS_THRESHOLD = 5;
    public static final double PREMIUM_RATE = 0.5;
    public static final int WEEKLY_REGULAR_MINUTES = 40 * 60;

    private final EmployeeRepository employeeRepository;

    // 매장 설정이 없으면 승인된 직원 수로 판단
    public boolean premiumApplies(Store store) {
        if (store.getFivePlusBusiness() != null) {
            return store.getFivePlusBusiness();
        }
        return employeeRepository.countByStoreIdAndStatus(store.getId(), EmployeeStatus.APPROVED) >= FIVE_PLUS_THRESHOLD;
    }
}
