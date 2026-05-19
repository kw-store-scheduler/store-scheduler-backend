package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.service.EmployeeService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    /**
     * 직원 등록 API
     */
    @PostMapping
    public ResponseEntity<Long> createEmployee(@RequestBody Employee employee) {
        Long employeeId = employeeService.registerEmployee(employee);
        return ResponseEntity.ok(employeeId);
    }

    /**
     * 전체 직원 조회 API (순환 참조 선제 방어)
     */
    @GetMapping
    public ResponseEntity<List<EmployeeResponseDto>> getAllEmployees() {
        List<Employee> findEmployees = employeeService.findEmployees();

        // 직원을 조회할 때 소속 매장 이름까지만 딱 정제해서 반환 (무한 루프 원천 차단)
        List<EmployeeResponseDto> result = findEmployees.stream()
                .map(e -> new EmployeeResponseDto(
                        e.getId(),
                        e.getName(),
                        e.getPhoneNumber(),
                        e.getHourlyWage(),
                        e.getStore() != null ? e.getStore().getName() : "소속 없음"
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 외부 노출용 안전한 직원 데이터 가방 (DTO)
     */
    @Data
    @AllArgsConstructor
    static class EmployeeResponseDto {
        private Long id;
        private String name;
        private String phoneNumber;
        private Integer hourlyWage;
        private String storeName; // 매장 객체 통째로가 아닌, 이름만 쏙 빼서 순환 참조 고리를 끊습니다.
    }
}