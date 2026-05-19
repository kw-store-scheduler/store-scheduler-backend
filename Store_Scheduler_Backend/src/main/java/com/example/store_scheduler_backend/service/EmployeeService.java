package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;

    /**
     * 신규 직원 등록
     */
    @Transactional
    public Long registerEmployee(Employee employee) {
        // 비즈니스 검증: 시급이 지나치게 낮게 설정되는 오염 데이터를 차단합니다.
        if (employee.getHourlyWage() == null || employee.getHourlyWage() < 0) {
            throw new IllegalArgumentException("올바르지 않은 시급 설정입니다.");
        }

        employeeRepository.save(employee);
        return employee.getId();
    }

    /**
     * 특정 매장에 소속된 전체 직원 조회
     */
    public List<Employee> findEmployees() {
        return employeeRepository.findAll();
    }
}