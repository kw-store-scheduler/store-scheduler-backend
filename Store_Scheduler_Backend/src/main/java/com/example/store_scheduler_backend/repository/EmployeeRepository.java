package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    // 이 한 줄이 있어야 파이썬이 준 이름("참빛")으로 DB에서 직원을 꺼내올 수 있습니다.
    Optional<Employee> findByName(String name);
}