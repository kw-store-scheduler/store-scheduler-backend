package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional
    public Employee joinStore(String storeCode, String name, String phoneNumber, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        Store store = storeRepository.findByStoreCode(storeCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 매장 코드입니다."));

        employeeRepository.findByStoreIdAndUserId(store.getId(), user.getId()).ifPresent(e -> {
            throw new IllegalArgumentException("이미 해당 매장에 신청한 이력이 있습니다.");
        });

        return employeeRepository.save(new Employee(name, phoneNumber, store, user));
    }

    @Transactional
    public void approve(Long storeId, Long employeeId, int hourlyWage, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 신청만 처리할 수 있습니다.");
        }
        employee.approve(hourlyWage);
    }

    @Transactional
    public void reject(Long storeId, Long employeeId, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 신청만 처리할 수 있습니다.");
        }
        employee.reject();
    }

    public List<Employee> findApprovedByStore(Long storeId) {
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
    }

    public List<Employee> findPendingByStore(Long storeId) {
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.PENDING);
    }

    public List<Employee> findEmployees() {
        return employeeRepository.findAll();
    }

    @Transactional
    public void updateEmployee(Long storeId, Long employeeId, String name, String phoneNumber, Integer hourlyWage, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        employee.updateInfo(name, phoneNumber, hourlyWage);
    }

    public Employee findOne(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 직원입니다."));
    }

    private Store findStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다."));
    }

    private void verifyOwner(Store store, String username) {
        if (!store.getOwner().getUsername().equals(username)) {
            throw new IllegalArgumentException("해당 매장의 관리자만 접근할 수 있습니다.");
        }
    }
}
