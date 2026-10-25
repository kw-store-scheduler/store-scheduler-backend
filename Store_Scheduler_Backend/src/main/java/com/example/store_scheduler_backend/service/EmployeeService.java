package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final FcmService fcmService;
    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeProfileService profileService;

    @Transactional
    public Employee joinStore(String storeCode, String name, String phoneNumber, String username) {
        return joinStore(storeCode, name, phoneNumber, null, null, false, null, username);
    }

    @Transactional
    public Employee joinStore(String storeCode, String name, String phoneNumber, String nickname, LocalDate startDate,
                              boolean minor, EmploymentType employmentType, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        Store store = storeRepository.findByStoreCode(storeCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 매장 코드입니다."));

        employeeRepository.findByStoreIdAndUserId(store.getId(), user.getId()).ifPresent(e -> {
            throw new IllegalArgumentException("이미 해당 매장에 신청한 이력이 있습니다.");
        });

        Employee employee = new Employee(name, phoneNumber, store, user);
        employee.fillJoinProfile(nickname, startDate, minor, employmentType);
        Employee saved = employeeRepository.save(employee);
        fcmService.sendToDevice(store.getOwner().getDeviceToken(), "가입 요청",
                employee.getName() + "님이 " + store.getName() + " 가입을 요청했습니다.");
        return saved;
    }

    @Transactional
    public void approve(Long storeId, Long employeeId, int hourlyWage, EmploymentType employmentType, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 신청만 처리할 수 있습니다.");
        }
        employee.approve(hourlyWage, employmentType);

        fcmService.sendToDevice(
                employee.getUser().getDeviceToken(),
                "가입 신청 승인",
                store.getName() + "에서 가입 신청이 승인되었습니다."
        );
    }

    @Transactional
    public void reject(Long storeId, Long employeeId, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 신청만 처리할 수 있습니다.");
        }
        employee.reject();

        fcmService.sendToDevice(
                employee.getUser().getDeviceToken(),
                "가입 신청 거절",
                store.getName() + "에서 가입 신청이 거절되었습니다."
        );
    }

    public List<Employee> findApprovedByStore(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
    }

    public List<Employee> findPendingByStore(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.PENDING);
    }

    @Transactional
    public void updateEmployee(Long storeId, Long employeeId, String name, String phoneNumber, Integer hourlyWage,
                               EmploymentType employmentType, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        Integer beforeWage = employee.getHourlyWage();
        EmploymentType beforeType = employee.getEmploymentType();
        employee.updateInfo(name, phoneNumber, hourlyWage, employmentType);
        if (hourlyWage != null && !hourlyWage.equals(beforeWage)) {
            profileService.recordChange(employee, "시급 " + beforeWage + "원 → " + hourlyWage + "원");
        }
        if (employmentType != null && employmentType != beforeType) {
            profileService.recordChange(employee, "고용 형태 " + (employmentType == EmploymentType.FULL_TIME ? "직원" : "알바") + "(으)로 변경");
        }
    }

    public Employee findOne(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 직원입니다."));
    }

}
