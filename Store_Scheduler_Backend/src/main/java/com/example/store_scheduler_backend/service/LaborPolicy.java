package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

// 근로기준법 가산수당 적용 여부. 상시 5인 미만이면 연장·야간 가산이 빠지고 주휴수당만 계산한다
@Component
@RequiredArgsConstructor
public class LaborPolicy {

    public static final int FIVE_PLUS_THRESHOLD = 5;
    public static final double PREMIUM_RATE = 0.5;
    public static final int WEEKLY_REGULAR_MINUTES = 40 * 60;

    private static final Map<Integer, Integer> MINIMUM_WAGE = new TreeMap<>(Map.of(2026, 10_320, 2027, 10_700));

    private final EmployeeRepository employeeRepository;

    // 표에 없는 해는 가장 가까운 해의 금액을 쓴다 (새 연도는 추가 필요)
    static int minimumWage(int year) {
        TreeMap<Integer, Integer> wages = (TreeMap<Integer, Integer>) MINIMUM_WAGE;
        Map.Entry<Integer, Integer> known = wages.floorEntry(year);
        return known != null ? known.getValue() : wages.firstEntry().getValue();
    }

    // 수습 감액 하한: 최저임금의 90%
    static int probationWageFloor(int year) {
        return (int) Math.ceil(minimumWage(year) * 0.9);
    }

    // 매장 설정이 없으면 승인된 직원 수로 판단
    public boolean premiumApplies(Store store) {
        if (store.getFivePlusBusiness() != null) {
            return store.getFivePlusBusiness();
        }
        return employeeRepository.countByStoreIdAndStatus(store.getId(), EmployeeStatus.APPROVED) >= FIVE_PLUS_THRESHOLD;
    }
}
