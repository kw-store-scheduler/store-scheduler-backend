package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 단위 접근 권한 검증을 담당한다.
 * 각 서비스에 중복 구현되어 있던 "매장 조회 + 소유자 확인" 로직을 한곳으로 모은 것.
 * 권한이 없으면 AccessDeniedException을 던져 403으로 응답되게 한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StoreAccessGuard {

    private final StoreRepository storeRepository;
    private final EmployeeRepository employeeRepository;

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
        if (!isOwner(store, username)) {
            throw new AccessDeniedException("해당 매장의 관리자만 접근할 수 있습니다.");
        }
    }

    /**
     * 요청자가 해당 매장의 관리자이거나 승인된 직원인지 검증한다.
     * 근무표·근무 시간대처럼 매장 구성원이라면 누구나 볼 수 있는 데이터의 조회에 사용한다.
     */
    public Store verifyMember(Long storeId, String username) {
        Store store = getStore(storeId);
        boolean approvedEmployee = employeeRepository
                .existsByStoreIdAndUser_UsernameAndStatus(storeId, username, EmployeeStatus.APPROVED);
        if (!isOwner(store, username) && !approvedEmployee) {
            throw new AccessDeniedException("해당 매장 소속만 접근할 수 있습니다.");
        }
        return store;
    }

    /**
     * 요청자가 해당 매장의 승인된 직원인지 검증하고, 그 직원을 반환한다.
     */
    public Employee verifyApprovedEmployee(Long storeId, String username) {
        return employeeRepository.findByStoreIdAndUser_Username(storeId, username)
                .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
                .orElseThrow(() -> new AccessDeniedException("해당 매장의 승인된 직원만 이용할 수 있습니다."));
    }

    private boolean isOwner(Store store, String username) {
        return store.getOwner().getUsername().equals(username);
    }
}
